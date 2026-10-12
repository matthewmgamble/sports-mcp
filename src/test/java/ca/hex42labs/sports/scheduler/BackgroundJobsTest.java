package ca.hex42labs.sports.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.application.ScheduleSync;
import ca.hex42labs.sports.application.SyncPlanner;
import ca.hex42labs.sports.application.TeamAliases;
import ca.hex42labs.sports.application.TeamCatalog;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.mlb.MlbFixtures;
import ca.hex42labs.sports.provider.nhl.NhlFixtures;
import ca.hex42labs.sports.testing.FakeProvider;
import ca.hex42labs.sports.testing.Games;
import ca.hex42labs.sports.testing.MutableClock;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundJobsTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");

    @TempDir Path tmp;

    @Test
    void tickCatchesUpThenOnlyDoesWhatIsDue() {
        try (Database db = Database.open(tmp.resolve("b.db").toString())) {
            MutableClock clock = new MutableClock(T0);
            ObjectMapper json = new ObjectMapper();
            FakeProvider espn = new FakeProvider("espn");
            List.of("nfl", "nba", "ncaaf", "ncaab", "epl", "laliga", "bundesliga")
                    .forEach(
                            l ->
                                    espn.teams(
                                            l,
                                            ca.hex42labs.sports.provider.espn.EspnFixtures.teams(
                                                    l)));
            FakeProvider nhl = new FakeProvider("nhl").teams("nhl", NhlFixtures.teams());
            FakeProvider mlb = new FakeProvider("mlb").teams("mlb", MlbFixtures.teams());
            Providers providers = new Providers(List.of(espn, nhl, mlb));
            SyncStateRepository state = new SyncStateRepository(db);
            GameRepository games = new GameRepository(db, json);
            TeamCatalog teams =
                    new TeamCatalog(
                            TestLeagues.REGISTRY,
                            providers,
                            new TeamRepository(db, json),
                            state,
                            TeamAliases.bundled(),
                            clock);
            ScheduleSync sync = new ScheduleSync(providers, games, teams, state, clock, 14);
            games.upsertAll(
                    List.of(
                            Games.scheduled(
                                    "nhl:ancient",
                                    "nhl:tor",
                                    "nhl:mtl",
                                    T0.minus(Duration.ofDays(120)),
                                    T0)),
                    T0);

            BackgroundJobs jobs =
                    new BackgroundJobs(
                            TestLeagues.REGISTRY,
                            teams,
                            sync,
                            new SyncPlanner(sync, clock),
                            games,
                            clock,
                            Duration.ofDays(90));

            jobs.tick();
            assertThat(teams.all()).hasSizeGreaterThan(1_300);
            assertThat(nhl.scheduleCalls).containsExactly("nhl 2026-10-11..2026-10-26");
            assertThat(espn.scheduleCalls).hasSize(7);
            assertThat(games.findById("nhl:ancient")).isEmpty(); // retention ran

            jobs.tick(); // nothing due a minute later
            assertThat(nhl.scheduleCalls).hasSize(1);
            assertThat(nhl.teamCalls.get()).isEqualTo(1);

            clock.advance(Duration.ofMinutes(31));
            jobs.tick();
            assertThat(nhl.scheduleCalls)
                    .containsExactly("nhl 2026-10-11..2026-10-26", "nhl 2026-10-11..2026-10-14");
        }
    }
}
