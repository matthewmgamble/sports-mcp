package ca.hex42labs.sports.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.application.TeamResolver.Resolution;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Resolution against the real team lists of all nine leagues. */
class TeamResolverTest {

    @TempDir static Path tmp;
    private static Database db;
    private static TeamResolver resolver;

    @BeforeAll
    static void setUp() {
        db = Database.open(tmp.resolve("r.db").toString());
        FakeProvider espn = new FakeProvider("espn");
        List.of("nfl", "nba", "ncaaf", "ncaab", "epl", "laliga", "bundesliga")
                .forEach(l -> espn.teams(l, EspnFixtures.teams(l)));
        TeamCatalog catalog =
                new TeamCatalog(
                        TestLeagues.REGISTRY,
                        new Providers(
                                List.of(
                                        espn,
                                        new FakeProvider("nhl").teams("nhl", NhlFixtures.teams()),
                                        new FakeProvider("mlb").teams("mlb", MlbFixtures.teams()))),
                        new TeamRepository(db, new ObjectMapper()),
                        new SyncStateRepository(db),
                        TeamAliases.bundled(),
                        Clock.fixed(Instant.parse("2026-10-12T18:00:00Z"), ZoneOffset.UTC));
        catalog.syncStale(Duration.ofDays(1), Duration.ofMinutes(5));
        resolver = new TeamResolver(catalog);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @ParameterizedTest(name = "\"{0}\" in {1} -> {2}")
    @CsvSource(
            nullValues = "-",
            value = {
                "nhl:tor,          -,          nhl:tor",
                "NHL:TOR,          -,          nhl:tor",
                "leafs,            -,          nhl:tor",
                "Maple Leafs,      -,          nhl:tor",
                "habs,             -,          nhl:mtl",
                "Green Bay,        nfl,        nfl:gb",
                "packers,          -,          nfl:gb",
                "jays,             -,          mlb:tor",
                "Blue Jays,        -,          mlb:tor",
                "toronto,          nhl,        nhl:tor",
                "toronto,          mlb,        mlb:tor",
                "TOR,              nba,        nba:tor",
                "raptors,          -,          nba:tor",
                "kings,            nhl,        nhl:la",
                "kings,            nba,        nba:sac",
                "barca,            -,          laliga:83",
                "atletico,         -,          laliga:1068",
                "Atlético Madrid,  -,          laliga:1068",
                "bvb,              -,          bundesliga:124",
                "man utd,          -,          epl:360",
                "Man City,         -,          epl:382",
                "49ers,            -,          nfl:sf",
                "Brighton & Hove Albion, -,    epl:331",
            })
    void resolves(String input, String league, String expected) {
        assertThat(resolver.resolve(input, league))
                .isInstanceOfSatisfying(
                        Resolution.Match.class, m -> assertThat(m.team().id()).isEqualTo(expected));
    }

    @ParameterizedTest
    @CsvSource({"toronto", "TOR", "kings", "rangers", "jets", "panthers", "Green Bay"})
    void ambiguousAcrossLeaguesListsCandidates(String input) {
        assertThat(resolver.resolve(input, null))
                .isInstanceOfSatisfying(
                        Resolution.Ambiguous.class,
                        a -> assertThat(a.candidates()).hasSizeBetween(2, 10));
    }

    @Test
    void ambiguousTorontoIncludesEveryTorontoTeam() {
        var a = (Resolution.Ambiguous) resolver.resolve("toronto", null);
        assertThat(a.candidates()).extracting(Team::id).contains("nhl:tor", "nba:tor", "mlb:tor");
    }

    @Test
    void unknownAndBlankAreNotFound() {
        assertThat(resolver.resolve("zzzz quidditch", null))
                .isInstanceOf(Resolution.NotFound.class);
        assertThat(resolver.resolve("  ", null)).isInstanceOf(Resolution.NotFound.class);
        // Well-formed id that doesn't exist falls through to text matching, then not found.
        assertThat(resolver.resolve("nhl:zzz", null)).isInstanceOf(Resolution.NotFound.class);
        // An id outside the requested league is not a match.
        assertThat(resolver.resolve("nhl:tor", "nba")).isNotInstanceOf(Resolution.Match.class);
    }

    @Test
    void searchRanksExactMatchesFirst() {
        List<Team> hits = resolver.search("pack", "nfl", 5);
        assertThat(hits).extracting(Team::id).first().isEqualTo("nfl:gb");
        assertThat(resolver.search(null, "nhl", 100)).hasSize(32);
        assertThat(resolver.search("bay", null, 20))
                .extracting(Team::id)
                .contains("nfl:gb", "nhl:tb");
    }

    @Test
    void normalisationFoldsAccentsAndPunctuation() {
        assertThat(TeamResolver.normalize("Atlético  Madrid!")).isEqualTo("atletico madrid");
        assertThat(TeamResolver.normalize("A's")).isEqualTo("a s");
        assertThat(TeamResolver.normalize("Brighton & Hove")).isEqualTo("brighton and hove");
    }
}
