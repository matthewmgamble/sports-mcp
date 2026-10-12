package ca.hex42labs.sports.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.testing.Games;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-12T18:00:00Z");
    private static final Instant KICKOFF = Instant.parse("2026-10-13T00:15:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;

    private Game mnf() {
        return Games.scheduled("nfl:401872994", "nfl:lar", "nfl:buf", KICKOFF, T0);
    }

    @Test
    void roundTripsEveryField() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            Game live = Games.live(mnf(), 14, 10, "5:42", T0.plusSeconds(60));
            repo.upsertAll(List.of(live), T0);
            assertThat(repo.findById(live.id())).contains(live);
        }
    }

    @Test
    void duplicateEventsNeverDuplicateRows() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            repo.upsertAll(List.of(mnf(), mnf()), T0);
            repo.upsertAll(List.of(mnf()), T0.plusSeconds(10));
            assertThat(repo.count()).isEqualTo(1);
        }
    }

    @Test
    void newerFetchWinsAndOlderFetchIsIgnored() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            Game fresh = Games.live(mnf(), 21, 17, "2:00", T0.plusSeconds(120));
            Game stale = Games.live(mnf(), 14, 10, "5:42", T0.plusSeconds(60));
            assertThat(repo.upsertAll(List.of(fresh), T0)).isEqualTo(1);
            // A slow request that started earlier finishes later: must not roll the score back.
            assertThat(repo.upsertAll(List.of(stale), T0)).isZero();
            assertThat(repo.findById(fresh.id()).orElseThrow().homeScore()).isEqualTo(21);
        }
    }

    @Test
    void rescheduleAndPostponementUpdateInPlace() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            repo.upsertAll(List.of(mnf()), T0);
            Instant makeup = KICKOFF.plus(Duration.ofDays(1));
            repo.upsertAll(
                    List.of(
                            Games.rescheduled(
                                    mnf(), makeup, GameStatus.POSTPONED, T0.plusSeconds(5))),
                    T0);
            Game stored = repo.findById(mnf().id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(GameStatus.POSTPONED);
            assertThat(stored.startsAt()).isEqualTo(makeup);
            assertThat(repo.count()).isEqualTo(1);
        }
    }

    @Test
    void staleOriginalDateListingCannotRollBackTheMakeupGame() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            Instant makeup = KICKOFF.plus(Duration.ofDays(1));
            Game makeupFinal =
                    Games.rescheduled(mnf(), makeup, GameStatus.FINAL, T0.plusSeconds(10));
            repo.upsertAll(List.of(makeupFinal), T0);
            // Later fetch of the original date still lists it as postponed, with the old start.
            Game originalListing =
                    Games.rescheduled(mnf(), KICKOFF, GameStatus.POSTPONED, T0.plusSeconds(20));
            assertThat(repo.upsertAll(List.of(originalListing), T0)).isZero();
            Game stored = repo.findById(mnf().id()).orElseThrow();
            assertThat(stored.status()).isEqualTo(GameStatus.FINAL);
            assertThat(stored.startsAt()).isEqualTo(makeup);
            // But a game postponed again on its (current) date does update.
            Game postponedAgain =
                    Games.rescheduled(mnf(), makeup, GameStatus.POSTPONED, T0.plusSeconds(30));
            assertThat(repo.upsertAll(List.of(postponedAgain), T0)).isEqualTo(1);
        }
    }

    @Test
    void retentionDeletesOldGames() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            repo.upsertAll(
                    List.of(
                            Games.scheduled(
                                    "nfl:old",
                                    "nfl:gb",
                                    "nfl:chi",
                                    KICKOFF.minus(Duration.ofDays(100)),
                                    T0),
                            mnf()),
                    T0);
            assertThat(repo.deleteStartedBefore(KICKOFF.minus(Duration.ofDays(90)))).isEqualTo(1);
            assertThat(repo.countByLeague()).containsExactly(java.util.Map.entry("nfl", 1));
        }
    }

    @Test
    void survivesRestart() {
        String path = tmp.resolve("g.db").toString();
        try (Database db = Database.open(path)) {
            new GameRepository(db, JSON).upsertAll(List.of(mnf()), T0);
        }
        try (Database db = Database.open(path)) {
            assertThat(new GameRepository(db, JSON).findById(mnf().id())).contains(mnf());
        }
    }

    @Test
    void rangeQueriesByLeagueAndTeam() {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            List<Game> games = new ArrayList<>();
            for (int d = 0; d < 5; d++) {
                games.add(
                        Games.scheduled(
                                "nfl:" + (100 + d),
                                "nfl:gb",
                                "nfl:chi",
                                KICKOFF.plus(Duration.ofDays(7L * d)),
                                T0));
            }
            games.add(Games.scheduled("nhl:1", "nhl:tor", "nhl:mtl", KICKOFF, T0));
            repo.upsertAll(games, T0);

            assertThat(repo.findByLeague("nfl", KICKOFF, KICKOFF.plus(Duration.ofDays(14)), 10))
                    .extracting(Game::id)
                    .containsExactly("nfl:100", "nfl:101");
            assertThat(
                            repo.findByTeam(
                                    "nfl:chi",
                                    KICKOFF.minusSeconds(1),
                                    KICKOFF.plus(Duration.ofDays(60)),
                                    3))
                    .hasSize(3);
            assertThat(repo.findByTeam("nhl:tor", KICKOFF, KICKOFF.plusSeconds(1), 10)).hasSize(1);
        }
    }

    @Test
    void concurrentUpsertsAreSerialisedSafely() throws Exception {
        try (Database db = Database.open(tmp.resolve("g.db").toString())) {
            var repo = new GameRepository(db, JSON);
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                tasks.add(
                        () ->
                                repo.upsertAll(
                                        List.of(Games.live(mnf(), n, 0, "1:00", T0.plusSeconds(n))),
                                        T0));
            }
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (var f : pool.invokeAll(tasks)) {
                    f.get();
                }
            }
            assertThat(repo.count()).isEqualTo(1);
            // Whatever the interleaving, the newest fetch is what's stored.
            assertThat(repo.findById(mnf().id()).orElseThrow().homeScore()).isEqualTo(19);
        }
    }

    @Test
    void timestampsSortLexically() {
        assertThat(Timestamps.format(Instant.parse("2026-10-12T13:12:19Z")))
                .isEqualTo("2026-10-12T13:12:19.000Z")
                .isLessThan(Timestamps.format(Instant.parse("2026-10-12T13:12:19.415453Z")));
    }
}
