package ca.hex42labs.sports.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.ResilientProvider;
import ca.hex42labs.sports.testing.FakeProvider;
import ca.hex42labs.sports.testing.Games;
import ca.hex42labs.sports.testing.MutableClock;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScoreRefreshCoordinatorTest {

    /** 19:00 Eastern, Monday Oct 12. */
    private static final Instant T0 = Instant.parse("2026-10-12T23:00:00Z");

    private static final League NFL = TestLeagues.get("nfl");

    @TempDir Path tmp;
    private Database db;
    private GameRepository games;
    private TeamCatalog teams;
    private final MutableClock clock = new MutableClock(T0);
    private final FakeProvider espn = new FakeProvider("espn");
    private final FakeProvider nhl = new FakeProvider("nhl");
    private final FakeProvider mlb = new FakeProvider("mlb");

    /** What "upstream" currently reports, by game id. */
    private final Map<String, Game> upstream = new ConcurrentHashMap<>();

    private ScoreRefreshCoordinator coordinator;

    @BeforeEach
    void setUp() {
        db = Database.open(tmp.resolve("c.db").toString());
        ObjectMapper json = new ObjectMapper();
        games = new GameRepository(db, json);
        teams =
                new TeamCatalog(
                        TestLeagues.REGISTRY,
                        new Providers(List.of(espn, nhl, mlb)),
                        new TeamRepository(db, json),
                        new SyncStateRepository(db),
                        TeamAliases.bundled(),
                        clock);
        FakeProvider.ScoreboardFn fromUpstream = (league, bucket) -> batch(league, bucket);
        espn.scoreboard(fromUpstream);
        nhl.scoreboard(fromUpstream);
        mlb.scoreboard(fromUpstream);
        coordinator = coordinator(new Providers(List.of(espn, nhl, mlb)));
    }

    private ScoreRefreshCoordinator coordinator(Providers providers) {
        return new ScoreRefreshCoordinator(
                TestLeagues.REGISTRY,
                providers,
                games,
                teams,
                new FreshnessPolicy(FreshnessPolicy.Settings.defaults()),
                clock,
                new ScoreRefreshCoordinator.Settings(
                        Duration.ofSeconds(5), Duration.ofSeconds(2), 14));
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
        db.close();
    }

    /** Upstream's current view of a league-day, stamped with the current time. */
    private ProviderBatch batch(League league, LocalDate bucket) {
        List<Game> out = new ArrayList<>();
        for (Game g : upstream.values()) {
            if (g.league().equals(league.id())
                    && ScoreRefreshCoordinator.bucketOf(g).equals(bucket)) {
                out.add(restamp(g, clock.instant()));
            }
        }
        return new ProviderBatch(league.id(), bucket, out, List.of(), clock.instant());
    }

    private static Game restamp(Game g, Instant fetchedAt) {
        return new Game(
                g.id(),
                g.league(),
                g.provider(),
                g.providerEventId(),
                g.season(),
                g.seasonType(),
                g.homeTeamId(),
                g.awayTeamId(),
                g.startsAt(),
                g.status(),
                g.statusDetail(),
                g.period(),
                g.periodType(),
                g.clock(),
                g.homeScore(),
                g.awayScore(),
                g.homeShootout(),
                g.awayShootout(),
                g.venue(),
                g.broadcasts(),
                g.situation(),
                fetchedAt,
                g.providerUpdatedAt());
    }

    /**
     * Stores {@code g} as fetched {@code ageSeconds} ago, and makes upstream report {@code now}.
     */
    private Game stored(Game g, long ageSeconds, Game now) {
        Game old = restamp(g, clock.instant().minusSeconds(ageSeconds));
        games.upsertAll(List.of(old), clock.instant());
        upstream.put(now.id(), now);
        return old;
    }

    private Game liveNfl(String id, int home, int away) {
        Game base = Games.scheduled(id, "nfl:lar", "nfl:buf", T0.minus(Duration.ofHours(1)), T0);
        return Games.live(base, home, away, "5:00", T0);
    }

    // ------------------------------------------------------------ acceptance

    @Test
    void hundredConcurrentRequestsMakeOneUpstreamCall() throws Exception {
        Game stale = stored(liveNfl("nfl:1", 7, 3), 60, liveNfl("nfl:1", 14, 3));
        CountDownLatch release = new CountDownLatch(1);
        espn.scoreboard(
                (league, bucket) -> {
                    try {
                        release.await(
                                5, TimeUnit.SECONDS); // hold the fetch open so everyone piles on
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return batch(league, bucket);
                });
        List<Future<Refreshed<List<Game>>>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 0; i < 100; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    return coordinator.ensureFresh(List.of(stale), false);
                                }));
            }
            go.countDown();
            Thread.sleep(200);
            release.countDown();
            for (var f : results) {
                Refreshed<List<Game>> r = f.get(10, TimeUnit.SECONDS);
                assertThat(r.data().get(0).homeScore()).isEqualTo(14);
                assertThat(r.stale()).isFalse();
            }
        }
        assertThat(espn.scoreboardCalls).containsExactly("nfl@2026-10-12");
    }

    @Test
    void openCircuitServesLastSnapshotMarkedStale() {
        Game snapshot = stored(liveNfl("nfl:1", 7, 3), 60, liveNfl("nfl:1", 14, 3));
        ResilientProvider guarded = ResilientProvider.wrap(espn);
        guarded.breaker().transitionToOpenState();
        coordinator.close();
        coordinator = coordinator(new Providers(List.of(guarded, nhl, mlb)));

        Refreshed<Optional<Game>> r = coordinator.game("nfl:1", false);

        assertThat(r.data()).hasValueSatisfying(g -> assertThat(g.homeScore()).isEqualTo(7));
        assertThat(r.stale()).isTrue();
        assertThat(r.staleReason()).contains("circuit open");
        assertThat(r.asOf()).isEqualTo(snapshot.fetchedAt());
        assertThat(espn.scoreboardCalls).isEmpty();
    }

    // ------------------------------------------------------------ freshness

    @Test
    void freshDataIsServedWithoutCallingUpstream() {
        Game g = stored(liveNfl("nfl:1", 7, 3), 10, liveNfl("nfl:1", 14, 3));
        Refreshed<List<Game>> r = coordinator.ensureFresh(List.of(g), false);
        assertThat(r.data().get(0).homeScore()).isEqualTo(7);
        assertThat(r.stale()).isFalse();
        assertThat(espn.scoreboardCalls).isEmpty();
    }

    @Test
    void liveTtlBoundary() {
        Game g = stored(liveNfl("nfl:1", 7, 3), 15, liveNfl("nfl:1", 14, 3));
        coordinator.ensureFresh(List.of(g), false);
        assertThat(espn.scoreboardCalls).isEmpty(); // exactly 15s: still fresh
        clock.advance(Duration.ofSeconds(1));
        Refreshed<List<Game>> r = coordinator.ensureFresh(List.of(g), false);
        assertThat(espn.scoreboardCalls).hasSize(1);
        assertThat(r.data().get(0).homeScore()).isEqualTo(14);
        assertThat(r.asOf()).isEqualTo(clock.instant());
    }

    @Test
    void gameStartTransitions() {
        Game later =
                Games.scheduled("nfl:2", "nfl:lar", "nfl:buf", T0.plus(Duration.ofMinutes(61)), T0);
        stored(later, 120, later);
        coordinator.ensureFresh(games.findByIds(List.of("nfl:2")), false);
        assertThat(espn.scoreboardCalls)
                .as("61 min out, 2 min old: background covers it")
                .isEmpty();

        clock.advance(Duration.ofMinutes(2)); // 59 min to start, data now 4 min old
        coordinator.ensureFresh(games.findByIds(List.of("nfl:2")), false);
        assertThat(espn.scoreboardCalls).as("inside pregame window").hasSize(1);

        clock.advance(Duration.ofMinutes(60)); // past start, upstream still says scheduled
        coordinator.ensureFresh(games.findByIds(List.of("nfl:2")), false);
        assertThat(espn.scoreboardCalls).as("overdue start polls every 30s").hasSize(2);
        clock.advance(Duration.ofSeconds(20));
        coordinator.ensureFresh(games.findByIds(List.of("nfl:2")), false);
        assertThat(espn.scoreboardCalls).hasSize(2);
        clock.advance(Duration.ofSeconds(11));
        coordinator.ensureFresh(games.findByIds(List.of("nfl:2")), false);
        assertThat(espn.scoreboardCalls).hasSize(3);
    }

    @Test
    void forcedRefreshRespectsTheFloor() {
        Game g = stored(liveNfl("nfl:1", 7, 3), 6, liveNfl("nfl:1", 14, 3));
        coordinator.ensureFresh(List.of(g), true);
        assertThat(espn.scoreboardCalls).hasSize(1);
        clock.advance(Duration.ofSeconds(3));
        coordinator.ensureFresh(games.findByIds(List.of("nfl:1")), true);
        assertThat(espn.scoreboardCalls).as("within 5s floor").hasSize(1);
        clock.advance(Duration.ofSeconds(3));
        coordinator.ensureFresh(games.findByIds(List.of("nfl:1")), true);
        assertThat(espn.scoreboardCalls).hasSize(2);
    }

    @Test
    void failuresAreSharedAndNotRetriedWithinTheFloor() {
        Game g = stored(liveNfl("nfl:1", 7, 3), 60, liveNfl("nfl:1", 14, 3));
        espn.scoreboard(
                (l, d) -> {
                    throw new ProviderException(
                            "espn",
                            ProviderException.Kind.HTTP_STATUS,
                            503,
                            "HTTP 503 /football/nfl/scoreboard",
                            null);
                });
        Refreshed<List<Game>> first = coordinator.ensureFresh(List.of(g), false);
        Refreshed<List<Game>> second = coordinator.ensureFresh(List.of(g), false);
        assertThat(first.stale()).isTrue();
        assertThat(first.staleReason()).contains("503");
        assertThat(second.stale()).isTrue();
        assertThat(espn.scoreboardCalls).hasSize(1);
    }

    @Test
    void slowUpstreamServesStaleAfterWaitingThenLandsForNextRequest() throws Exception {
        Game g = stored(liveNfl("nfl:1", 7, 3), 60, liveNfl("nfl:1", 14, 3));
        CountDownLatch release = new CountDownLatch(1);
        espn.scoreboard(
                (league, bucket) -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return batch(league, bucket);
                });
        long start = System.nanoTime();
        Refreshed<List<Game>> r = coordinator.ensureFresh(List.of(g), false);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
        assertThat(r.stale()).isTrue();
        assertThat(r.staleReason()).contains("still waiting");
        assertThat(r.data().get(0).homeScore()).isEqualTo(7);
        release.countDown();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> games.findById("nfl:1").orElseThrow().homeScore() == 14);
    }

    // ------------------------------------------------------------ buckets and clocks

    @Test
    void gameRunningPastMidnightRefreshesYesterdaysBucket() {
        // Kickoff 22:30 Eastern Oct 12; it's now 00:30 Eastern Oct 13.
        Game base =
                Games.scheduled(
                        "nfl:9", "nfl:lar", "nfl:buf", Instant.parse("2026-10-13T02:30:00Z"), T0);
        clock.advance(Duration.ofMinutes(330)); // 04:30Z = 00:30 ET Oct 13
        Game live = Games.live(base, 21, 20, "2:00", clock.instant());
        stored(live, 60, Games.live(base, 24, 20, "1:00", clock.instant()));
        Refreshed<List<Game>> r = coordinator.ensureFresh(games.findByIds(List.of("nfl:9")), false);
        assertThat(espn.scoreboardCalls).containsExactly("nfl@2026-10-12");
        assertThat(r.data().get(0).homeScore()).isEqualTo(24);
        assertThat(coordinator.today()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void bucketsFollowEasternTimeAcrossTheDstChange() {
        // Nov 1 2026: 02:00 EDT falls back to 01:00 EST.
        assertThat(bucket("2026-11-01T03:30:00Z"))
                .isEqualTo(LocalDate.of(2026, 10, 31)); // 23:30 EDT Oct 31
        assertThat(bucket("2026-11-01T04:00:00Z"))
                .isEqualTo(LocalDate.of(2026, 11, 1)); // 00:00 EDT
        assertThat(bucket("2026-11-01T05:30:00Z"))
                .isEqualTo(LocalDate.of(2026, 11, 1)); // 01:30 EDT
        assertThat(bucket("2026-11-01T06:30:00Z"))
                .isEqualTo(LocalDate.of(2026, 11, 1)); // 01:30 EST (again)
        assertThat(bucket("2026-11-02T04:30:00Z"))
                .isEqualTo(LocalDate.of(2026, 11, 1)); // 23:30 EST Nov 1
        assertThat(bucket("2026-11-02T05:00:00Z"))
                .isEqualTo(LocalDate.of(2026, 11, 2)); // midnight EST
    }

    private static LocalDate bucket(String startsAt) {
        return ScoreRefreshCoordinator.bucketOf(
                Games.scheduled("nfl:x", "nfl:a", "nfl:b", Instant.parse(startsAt), Instant.EPOCH));
    }

    @Test
    void settledOldGamesAreNeverRefreshedOnRequest() {
        Game old =
                Games.rescheduled(
                        liveNfl("nfl:3", 1, 0),
                        T0.minus(Duration.ofDays(20)),
                        GameStatus.FINAL,
                        T0);
        stored(old, 86_400 * 19, old);
        Refreshed<List<Game>> r = coordinator.ensureFresh(games.findByIds(List.of("nfl:3")), true);
        assertThat(espn.scoreboardCalls).isEmpty();
        assertThat(r.stale()).isFalse();
    }

    // ------------------------------------------------------------ day / live / per-game

    @Test
    void emptyDayFetchesTheBucketAtMostEveryTwoMinutes() {
        upstream.put(
                "nfl:5",
                Games.scheduled("nfl:5", "nfl:lar", "nfl:buf", T0.plus(Duration.ofHours(2)), T0));
        Refreshed<List<Game>> r = coordinator.day(NFL, LocalDate.of(2026, 10, 12), false);
        assertThat(r.data()).extracting(Game::id).containsExactly("nfl:5");

        // A day upstream says is empty: one probe, then quiet for 2 minutes.
        coordinator.day(NFL, LocalDate.of(2026, 10, 14), false);
        coordinator.day(NFL, LocalDate.of(2026, 10, 14), false);
        assertThat(espn.scoreboardCalls).containsExactly("nfl@2026-10-12", "nfl@2026-10-14");
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        coordinator.day(NFL, LocalDate.of(2026, 10, 14), false);
        assertThat(espn.scoreboardCalls).hasSize(3);
    }

    @Test
    void liveReturnsOnlyGamesInProgressAfterRefresh() {
        stored(liveNfl("nfl:1", 7, 3), 60, liveNfl("nfl:1", 14, 3));
        Game justFinished = liveNfl("nfl:2", 20, 17);
        stored(
                justFinished,
                60,
                Games.rescheduled(justFinished, justFinished.startsAt(), GameStatus.FINAL, T0));
        Game starting =
                Games.scheduled("nfl:4", "nfl:gb", "nfl:chi", T0.minus(Duration.ofMinutes(5)), T0);
        Game nowLive = Games.live(starting, 0, 0, "15:00", T0);
        stored(starting, 60, nowLive);
        Game tomorrow =
                Games.scheduled("nfl:6", "nfl:gb", "nfl:chi", T0.plus(Duration.ofDays(1)), T0);
        stored(tomorrow, 60, tomorrow);

        Refreshed<List<Game>> r = coordinator.live("nfl", null, false);
        assertThat(r.data()).extracting(Game::id).containsExactlyInAnyOrder("nfl:1", "nfl:4");
        assertThat(r.stale()).isFalse();
        assertThat(espn.scoreboardCalls).containsExactly("nfl@2026-10-12");

        assertThat(coordinator.live("nfl", "nfl:gb", false).data())
                .extracting(Game::id)
                .containsExactly("nfl:4");
    }

    @Test
    void liveMlbGamesGetTheirPerGameFeed() {
        Game base =
                Games.scheduled("mlb:777", "mlb:mil", "mlb:lad", T0.minus(Duration.ofHours(2)), T0);
        stored(Games.live(base, 1, 1, null, T0), 60, Games.live(base, 2, 1, null, T0));
        mlb.refreshGame(g -> Optional.of(Games.live(g, 3, 1, null, clock.instant())));

        Refreshed<Optional<Game>> r = coordinator.game("mlb:777", false);

        assertThat(mlb.scoreboardCalls).containsExactly("mlb@2026-10-12");
        assertThat(mlb.refreshGameCalls).containsExactly("mlb:777");
        assertThat(r.data()).hasValueSatisfying(g -> assertThat(g.homeScore()).isEqualTo(3));
    }

    @Test
    void unknownGameIsEmptyNotAnError() {
        Refreshed<Optional<Game>> r = coordinator.game("nfl:nope", false);
        assertThat(r.data()).isEmpty();
        assertThat(r.stale()).isFalse();
    }
}
