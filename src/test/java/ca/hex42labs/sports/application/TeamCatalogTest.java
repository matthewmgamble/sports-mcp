package ca.hex42labs.sports.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.SyncState;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.espn.EspnFixtures;
import ca.hex42labs.sports.provider.mlb.MlbFixtures;
import ca.hex42labs.sports.provider.nhl.NhlFixtures;
import ca.hex42labs.sports.testing.FakeProvider;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamCatalogTest {

    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");
    private static final List<String> ESPN_LEAGUES =
            List.of("nfl", "nba", "ncaaf", "ncaab", "epl", "laliga", "bundesliga");

    @TempDir Path tmp;

    private final FakeProvider espn = new FakeProvider("espn");
    private final FakeProvider nhl = new FakeProvider("nhl").teams("nhl", NhlFixtures.teams());
    private final FakeProvider mlb = new FakeProvider("mlb").teams("mlb", MlbFixtures.teams());

    {
        ESPN_LEAGUES.forEach(l -> espn.teams(l, EspnFixtures.teams(l)));
    }

    private TeamCatalog catalog(Database db, Clock clock) {
        return new TeamCatalog(
                TestLeagues.REGISTRY,
                new Providers(List.of(espn, nhl, mlb)),
                new TeamRepository(db, new ObjectMapper()),
                new SyncStateRepository(db),
                TeamAliases.bundled(),
                clock);
    }

    @Test
    void allNineLeaguesLoadAndSurviveRestart() {
        String path = tmp.resolve("c.db").toString();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        int total;
        try (Database db = Database.open(path)) {
            TeamCatalog c = catalog(db, clock);
            c.load();
            c.syncStale(Duration.ofHours(24));
            for (String league : TestLeagues.REGISTRY.enabledIds()) {
                assertThat(c.byLeague(league)).as(league).isNotEmpty();
            }
            assertThat(c.find("nhl:tor").orElseThrow().aliases()).contains("leafs");
            total = c.all().size();
            assertThat(total).isGreaterThan(1_300);
        }
        try (Database db = Database.open(path)) {
            TeamCatalog c = catalog(db, clock);
            c.load(); // no sync: everything comes back from SQLite
            assertThat(c.all()).hasSize(total);
            assertThat(c.find("mlb:tor").orElseThrow().name()).isEqualTo("Toronto Blue Jays");
            assertThat(c.find("nhl:tor").orElseThrow().aliases()).contains("leafs");
        }
    }

    @Test
    void freshCatalogsAreNotRefetched() {
        try (Database db = Database.open(tmp.resolve("c.db").toString())) {
            TeamCatalog c = catalog(db, Clock.fixed(NOW, ZoneOffset.UTC));
            c.syncStale(Duration.ofHours(24));
            int calls = nhl.teamCalls.get();
            catalog(db, Clock.fixed(NOW.plus(Duration.ofHours(23)), ZoneOffset.UTC))
                    .syncStale(Duration.ofHours(24));
            assertThat(nhl.teamCalls.get()).isEqualTo(calls);
            catalog(db, Clock.fixed(NOW.plus(Duration.ofHours(25)), ZoneOffset.UTC))
                    .syncStale(Duration.ofHours(24));
            assertThat(nhl.teamCalls.get()).isEqualTo(calls + 1);
        }
    }

    @Test
    void failedSyncKeepsExistingTeamsAndRecordsError() {
        try (Database db = Database.open(tmp.resolve("c.db").toString())) {
            TeamCatalog c = catalog(db, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(c.sync(TestLeagues.get("nfl"))).isTrue();
            espn.failWith(
                    new ProviderException(
                            "espn", ProviderException.Kind.TIMEOUT, "timeout /teams", null));
            assertThat(c.sync(TestLeagues.get("nfl"))).isFalse();
            assertThat(c.byLeague("nfl")).hasSize(32);
            SyncState state =
                    new SyncStateRepository(db).find("nfl", SyncState.Kind.TEAMS).orElseThrow();
            assertThat(state.error()).contains("timeout");
            assertThat(state.lastSuccessAt()).isEqualTo(NOW);
        }
    }

    @Test
    void emptyTeamListIsTreatedAsFailure() {
        try (Database db = Database.open(tmp.resolve("c.db").toString())) {
            espn.teams("nfl", List.of());
            TeamCatalog c = catalog(db, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(c.sync(TestLeagues.get("nfl"))).isFalse();
            assertThat(
                            new SyncStateRepository(db)
                                    .find("nfl", SyncState.Kind.TEAMS)
                                    .orElseThrow()
                                    .error())
                    .isEqualTo("empty team list");
        }
    }

    @Test
    void teamsFromGamesFillGapsWithoutOverwriting() {
        try (Database db = Database.open(tmp.resolve("c.db").toString())) {
            TeamCatalog c = catalog(db, Clock.fixed(NOW, ZoneOffset.UTC));
            c.sync(TestLeagues.get("nhl"));
            Team thin =
                    new Team(
                            "nhl:tor",
                            "nhl",
                            "nhl",
                            "TOR",
                            "Maple Leafs",
                            null,
                            "TOR",
                            null,
                            null,
                            List.of());
            Team fcs =
                    new Team(
                            "ncaaf:9999",
                            "ncaaf",
                            "espn",
                            "9999",
                            "Tiny State",
                            null,
                            "TINY",
                            null,
                            null,
                            List.of());
            c.addFromGames(List.of(thin, fcs));
            assertThat(c.find("nhl:tor").orElseThrow().name()).isEqualTo("Toronto Maple Leafs");
            assertThat(c.find("ncaaf:9999")).isPresent();
            TeamCatalog reloaded = catalog(db, Clock.fixed(NOW, ZoneOffset.UTC));
            reloaded.load();
            assertThat(reloaded.find("ncaaf:9999")).isPresent();
            assertThat(reloaded.find("nhl:tor").orElseThrow().name())
                    .isEqualTo("Toronto Maple Leafs");
        }
    }
}
