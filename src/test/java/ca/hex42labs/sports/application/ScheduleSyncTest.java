package ca.hex42labs.sports.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.application.SyncPlanner.Due;
import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.SyncState;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.nhl.NhlFixtures;
import ca.hex42labs.sports.testing.FakeProvider;
import ca.hex42labs.sports.testing.Games;
import ca.hex42labs.sports.testing.MutableClock;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScheduleSyncTest {

    // 10:00 Eastern on Oct 12.
    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final League NHL = TestLeagues.get("nhl");

    @TempDir Path tmp;
    private Database db;
    private final MutableClock clock = new MutableClock(T0);
    private final FakeProvider nhl = new FakeProvider("nhl").teams("nhl", NhlFixtures.teams());
    private GameRepository games;
    private SyncStateRepository syncState;
    private ScheduleSync sync;
    private SyncPlanner planner;
    private TeamCatalog teams;

    @BeforeEach
    void setUp() {
        db = Database.open(tmp.resolve("s.db").toString());
        ObjectMapper json = new ObjectMapper();
        games = new GameRepository(db, json);
        syncState = new SyncStateRepository(db);
        Providers providers =
                new Providers(List.of(nhl, new FakeProvider("espn"), new FakeProvider("mlb")));
        teams =
                new TeamCatalog(
                        TestLeagues.REGISTRY,
                        providers,
                        new TeamRepository(db, json),
                        syncState,
                        TeamAliases.bundled(),
                        clock);
        sync = new ScheduleSync(providers, games, teams, syncState, clock, 14);
        planner = new SyncPlanner(sync, clock);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private ScheduleResult result(List<Game> g, List<String> failures) {
        Team tor =
                NhlFixtures.teams().stream()
                        .filter(t -> t.id().equals("nhl:tor"))
                        .findFirst()
                        .orElseThrow();
        Team mtl =
                NhlFixtures.teams().stream()
                        .filter(t -> t.id().equals("nhl:mtl"))
                        .findFirst()
                        .orElseThrow();
        return new ScheduleResult(g, List.of(tor, mtl), clock.instant(), failures);
    }

    private Game game(String id, Instant start) {
        return Games.scheduled(id, "nhl:tor", "nhl:mtl", start, clock.instant());
    }

    @Test
    void fullWindowIsYesterdayThroughHorizonInLeagueTime() {
        nhl.schedule("nhl", result(List.of(game("nhl:1", T0.plus(Duration.ofDays(1)))), List.of()));
        ScheduleSync.Outcome o = sync.full(NHL, false);
        assertThat(o.ok()).isTrue();
        assertThat(o.from()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(o.to()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(nhl.scheduleCalls).containsExactly("nhl 2026-10-11..2026-10-26");
        assertThat(games.findById("nhl:1")).isPresent();
        SyncState s = syncState.find("nhl", SyncState.Kind.SCHEDULE).orElseThrow();
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
        assertThat(s.windowStart()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(s.windowEnd()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(s.error()).isNull();
    }

    @Test
    void leagueTimezoneDecidesToday() {
        // 01:00 UTC Oct 13 is still Oct 12 in New York.
        clock.advance(Duration.ofHours(11));
        assertThat(sync.today(NHL)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(sync.today(TestLeagues.get("epl"))).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void nearTermIsNext48Hours() {
        ScheduleSync.Outcome o = sync.nearTerm(NHL, false);
        assertThat(o.from()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(o.to()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(syncState.find("nhl", SyncState.Kind.SCHEDULE_NEAR)).isPresent();
    }

    @Test
    void emptyOffseasonWindowIsSuccess() {
        assertThat(sync.full(NHL, false).ok()).isTrue();
        assertThat(syncState.find("nhl", SyncState.Kind.SCHEDULE).orElseThrow().lastSuccessAt())
                .isEqualTo(T0);
        assertThat(games.count()).isZero();
    }

    @Test
    void partialFailureSavesGamesButIsNotSuccess() {
        nhl.schedule(
                "nhl", result(List.of(game("nhl:1", T0)), List.of("2026-10-20: nhl: HTTP 503")));
        ScheduleSync.Outcome o = sync.full(NHL, false);
        assertThat(o.ok()).isFalse();
        assertThat(games.findById("nhl:1")).isPresent();
        SyncState s = syncState.find("nhl", SyncState.Kind.SCHEDULE).orElseThrow();
        assertThat(s.lastSuccessAt()).isNull();
        assertThat(s.error()).contains("503");
    }

    @Test
    void totalFailureKeepsExistingGames() {
        nhl.schedule("nhl", result(List.of(game("nhl:1", T0)), List.of()));
        sync.full(NHL, false);
        nhl.failSchedulesWith(
                new ProviderException("nhl", ProviderException.Kind.TIMEOUT, "timeout", null));
        clock.advance(Duration.ofHours(7));
        assertThat(sync.full(NHL, false).ok()).isFalse();
        assertThat(games.findById("nhl:1")).isPresent();
        SyncState s = syncState.find("nhl", SyncState.Kind.SCHEDULE).orElseThrow();
        assertThat(s.lastSuccessAt()).isEqualTo(T0);
        assertThat(s.error()).contains("timeout");
    }

    @Test
    void postponedGameUpdatesInPlace() {
        Game original = game("nhl:7", T0.plus(Duration.ofDays(2)));
        nhl.schedule("nhl", result(List.of(original), List.of()));
        sync.full(NHL, false);
        clock.advance(Duration.ofHours(6));
        nhl.schedule(
                "nhl",
                result(
                        List.of(
                                Games.rescheduled(
                                        original,
                                        original.startsAt(),
                                        GameStatus.POSTPONED,
                                        clock.instant())),
                        List.of()));
        sync.full(NHL, false);
        assertThat(games.findById("nhl:7").orElseThrow().status()).isEqualTo(GameStatus.POSTPONED);
        assertThat(games.count()).isEqualTo(1);
    }

    @Test
    void plannerSchedulesFullThenNearTermThenNothing() {
        assertThat(planner.due(NHL)).isEqualTo(Due.FULL); // never synced: catch up at startup
        sync.full(NHL, false);
        assertThat(planner.due(NHL)).isEqualTo(Due.NONE);
        clock.advance(Duration.ofMinutes(31));
        assertThat(planner.due(NHL)).isEqualTo(Due.NEAR_TERM);
        sync.nearTerm(NHL, false);
        assertThat(planner.due(NHL)).isEqualTo(Due.NONE);
        clock.advance(Duration.ofHours(6));
        assertThat(planner.due(NHL)).isEqualTo(Due.FULL);
    }

    @Test
    void plannerBacksOffWhileUpstreamIsFailing() {
        nhl.failSchedulesWith(
                new ProviderException("nhl", ProviderException.Kind.NETWORK, "down", null));
        sync.full(NHL, false);
        clock.advance(Duration.ofMinutes(2));
        assertThat(planner.due(NHL)).isEqualTo(Due.NONE);
        clock.advance(Duration.ofMinutes(4));
        assertThat(planner.due(NHL)).isEqualTo(Due.FULL);
    }

    @Test
    void concurrentSyncForSameLeagueIsSkippedNotDoubled() throws Exception {
        var gate = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        FakeProvider slow =
                new FakeProvider("nhl") {
                    @Override
                    public ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
                            throws ProviderException {
                        entered.countDown();
                        try {
                            gate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return super.fetchSchedule(league, from, to);
                    }
                };
        ScheduleSync s =
                new ScheduleSync(
                        new Providers(
                                List.of(slow, new FakeProvider("espn"), new FakeProvider("mlb"))),
                        games,
                        teams,
                        syncState,
                        clock,
                        14);
        Thread first = Thread.ofVirtual().start(() -> s.full(NHL, false));
        entered.await();
        assertThat(s.full(NHL, false).skipped()).isTrue();
        gate.countDown();
        first.join();
        assertThat(slow.scheduleCalls).hasSize(1);
    }
}
