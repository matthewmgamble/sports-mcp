package ca.hex42labs.sports.provider.mlb;

import static ca.hex42labs.sports.testing.WireMockFixtures.gz;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.hex42labs.sports.application.GameLinks;
import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.GameLinkRepository;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.provider.espn.EspnProvider;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * statsapi refuses (406) what the hosted server asks for beyond today; ESPN fills in, linked onto
 * statsapi's game ids. Real fixtures from both providers for the same days.
 */
@WireMockTest
class MlbWithEspnFallbackTest {

    private static final Instant NOW = Instant.parse("2026-09-26T16:00:00Z");
    private static final League MLB = TestLeagues.get("mlb");
    private static final LocalDate SEP22 = LocalDate.of(2026, 9, 22);
    private static final LocalDate SEP23 = LocalDate.of(2026, 9, 23);
    private static final LocalDate SEP25 = LocalDate.of(2026, 9, 25);

    @TempDir Path tmp;
    private Database db;
    private GameRepository games;
    private GameLinkRepository links;
    private MlbWithEspnFallback provider;
    private MlbProvider statsapi;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        db = Database.open(tmp.resolve("m.db").toString());
        games = new GameRepository(db, new ObjectMapper());
        links = new GameLinkRepository(db);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UpstreamHttp http = UpstreamHttp.withDefaults(new ObjectMapper(), "test");
        statsapi = new MlbProvider(http, wm.getHttpBaseUrl() + "/mlb", clock, Duration.ZERO);
        provider =
                new MlbWithEspnFallback(
                        statsapi,
                        new EspnProvider(http, wm.getHttpBaseUrl() + "/espn", clock),
                        new GameLinks(games, links, clock),
                        clock);
        // Anything not stubbed below is refused, like statsapi does to the hosted server.
        wm.getWireMock()
                .register(
                        get(urlPathMatching("/mlb/.*"))
                                .atPriority(10)
                                .willReturn(aResponse().withStatus(406)));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static StubMapping statsapiDay(WireMockRuntimeInfo wm, LocalDate day, String fixture) {
        return wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/mlb/schedule"))
                                .withQueryParam("date", equalTo(day.toString()))
                                .willReturn(gz(fixture)));
    }

    private static void espnDay(WireMockRuntimeInfo wm, LocalDate day, String fixture) {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/espn/baseball/mlb/scoreboard"))
                                .withQueryParam("dates", equalTo(day.toString().replace("-", "")))
                                .willReturn(gz(fixture)));
    }

    private static Game byId(List<Game> list, String id) {
        return list.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    private void save(List<Game> list) {
        games.upsertAll(list, NOW);
    }

    @Test
    void espnFillsTheDaysStatsapiRefuses(WireMockRuntimeInfo wm) throws Exception {
        statsapiDay(wm, SEP22, "mlb/schedule-20260922-postponed");
        espnDay(wm, SEP23, "espn/mlb/scoreboard-20260923-makeup");

        ScheduleResult r = provider.fetchSchedule(MLB, SEP22, SEP23);

        assertThat(r.complete()).isTrue();
        // 22nd from statsapi (canonical gamePks), 23rd from ESPN (nothing to link to: provisional).
        assertThat(r.games()).extracting(Game::id).contains("mlb:824785");
        Game makeup = byId(r.games(), "mlb:espn401923610");
        assertThat(makeup.provider()).isEqualTo("espn");
        assertThat(makeup.awayTeamId()).isEqualTo("mlb:tor");
        assertThat(makeup.homeTeamId()).isEqualTo("mlb:bal");
        assertThat(makeup.status()).isEqualTo(GameStatus.FINAL);
        // The catalog stays statsapi's: no ESPN team objects come through.
        assertThat(r.teams()).allSatisfy(t -> assertThat(t.provider()).isEqualTo("mlb"));
    }

    @Test
    void espnGamesLinkOntoStoredStatsapiGamesIncludingDoubleheaders(WireMockRuntimeInfo wm)
            throws Exception {
        // statsapi delivered the 25th earlier; today it refuses and ESPN answers.
        StubMapping earlier = statsapiDay(wm, SEP25, "mlb/schedule-20260925-doubleheaders");
        save(statsapi.fetchScoreboard(MLB, SEP25).games());
        wm.getWireMock().removeStubMapping(earlier);
        int stored = games.count();
        espnDay(wm, SEP25, "espn/mlb/scoreboard-20260925-doubleheaders");

        ProviderBatch b = provider.fetchScoreboard(MLB, SEP25);

        assertThat(b.games()).allSatisfy(g -> assertThat(g.provider()).isEqualTo("mlb"));
        Map<String, Game> byId = b.games().stream().collect(Collectors.toMap(Game::id, g -> g));
        // BAL@NYY doubleheader: statsapi says 20:05/20:10, ESPN 20:05/23:30. Paired in order.
        assertThat(byId.get("mlb:823491").startsAt())
                .isEqualTo(Instant.parse("2026-09-25T20:05:00Z"));
        assertThat(byId.get("mlb:823489").startsAt())
                .isEqualTo(Instant.parse("2026-09-25T23:30:00Z"));
        // CHC@BOS too.
        assertThat(byId).containsKeys("mlb:824703", "mlb:824706");
        save(b.games());
        assertThat(games.count()).as("no duplicates").isEqualTo(stored);
        assertThat(links.count()).isEqualTo(b.games().size());
        assertThat(links.gameIdsFor("espn", List.of("401817073")))
                .containsEntry("401817073", "mlb:823489");
    }

    @Test
    void statsapiReplacesProvisionalGamesAndTheirIdsStillResolve(WireMockRuntimeInfo wm)
            throws Exception {
        // Statsapi refused the 25th; ESPN supplied it under provisional ids.
        espnDay(wm, SEP25, "espn/mlb/scoreboard-20260925-doubleheaders");
        ProviderBatch fromEspn = provider.fetchScoreboard(MLB, SEP25);
        assertThat(fromEspn.games()).allSatisfy(g -> assertThat(g.id()).startsWith("mlb:espn"));
        save(fromEspn.games());
        int espnCount = games.count();

        // Later statsapi answers for the 25th.
        statsapiDay(wm, SEP25, "mlb/schedule-20260925-doubleheaders");
        ProviderBatch fromStatsapi = provider.fetchScoreboard(MLB, SEP25);
        save(fromStatsapi.games());

        assertThat(games.hasProvider("mlb", "espn")).as("provisional rows replaced").isFalse();
        assertThat(games.count()).isEqualTo(fromStatsapi.games().size());
        assertThat(espnCount).isEqualTo(fromStatsapi.games().size());
        // An id handed out while the game was provisional still finds it.
        Game g = games.findById("mlb:espn401817073").orElseThrow();
        assertThat(g.id()).isEqualTo("mlb:823489"); // BAL@NYY game 2
        assertThat(g.provider()).isEqualTo("mlb");
    }

    @Test
    void liveScoreboardStaysOnStatsapiWhenItAnswers(WireMockRuntimeInfo wm) throws Exception {
        statsapiDay(wm, SEP25, "mlb/schedule-20260925-doubleheaders");
        espnDay(wm, SEP25, "espn/mlb/scoreboard-20260925-doubleheaders");

        ProviderBatch b = provider.fetchScoreboard(MLB, SEP25);

        assertThat(b.games()).allSatisfy(g -> assertThat(g.provider()).isEqualTo("mlb"));
        wm.getWireMock().verifyThat(0, getRequestedFor(urlPathMatching("/espn/.*")));
    }

    @Test
    void provisionalGamesHaveNoPerGameFeed(WireMockRuntimeInfo wm) throws Exception {
        espnDay(wm, SEP25, "espn/mlb/scoreboard-20260925-doubleheaders");
        Game provisional = provider.fetchScoreboard(MLB, SEP25).games().getFirst();
        Game live =
                new Game(
                        provisional.id(),
                        provisional.league(),
                        provisional.provider(),
                        provisional.providerEventId(),
                        provisional.season(),
                        provisional.seasonType(),
                        provisional.homeTeamId(),
                        provisional.awayTeamId(),
                        provisional.startsAt(),
                        GameStatus.LIVE,
                        "Top 3rd",
                        3,
                        provisional.periodType(),
                        null,
                        1,
                        0,
                        null,
                        null,
                        provisional.venue(),
                        provisional.broadcasts(),
                        null,
                        provisional.fetchedAt(),
                        null);

        assertThat(provider.refreshGame(MLB, live)).isEmpty();
        wm.getWireMock().verifyThat(0, getRequestedFor(urlPathMatching("/mlb/game/.*")));
    }

    @Test
    void unseededPostseasonGamesAreSkipped(WireMockRuntimeInfo wm) throws Exception {
        LocalDate oct24 = LocalDate.of(2026, 10, 24);
        espnDay(wm, oct24, "espn/mlb/scoreboard-20261024-tbd");

        assertThat(provider.fetchScoreboard(MLB, oct24).games()).isEmpty();
    }

    @Test
    void teamScheduleFallsBackToEspnByAbbreviation(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/espn/baseball/mlb/teams/lad/schedule"))
                                .willReturn(gz("espn/mlb/team-schedule-lad")));
        Team lad =
                new Team(
                        "mlb:lad",
                        "mlb",
                        "mlb",
                        "119",
                        "Los Angeles Dodgers",
                        "Dodgers",
                        "LAD",
                        "Los Angeles",
                        null,
                        List.of());

        ScheduleResult r = provider.fetchTeamSchedule(MLB, lad);

        assertThat(r.games()).isNotEmpty();
        assertThat(r.games())
                .allSatisfy(
                        g ->
                                assertThat(List.of(g.homeTeamId(), g.awayTeamId()))
                                        .contains("mlb:lad"));
        assertThat(r.teams()).isEmpty();
    }

    @Test
    void statsapiOutageFillsTheWholeWindowFromEspn(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathMatching("/mlb/.*"))
                                .atPriority(1)
                                .willReturn(aResponse().withStatus(503)));
        espnDay(wm, SEP22, "espn/mlb/scoreboard-20260922-postponed");
        espnDay(wm, SEP23, "espn/mlb/scoreboard-20260923-makeup");

        ScheduleResult r = provider.fetchSchedule(MLB, SEP22, SEP23);

        assertThat(r.complete()).isTrue();
        assertThat(r.games()).hasSizeGreaterThan(20);
        assertThat(r.games()).allSatisfy(g -> assertThat(g.id()).startsWith("mlb:espn"));
    }

    @Test
    void retentionDropsLinksToDeletedGames(WireMockRuntimeInfo wm) throws Exception {
        StubMapping earlier = statsapiDay(wm, SEP25, "mlb/schedule-20260925-doubleheaders");
        save(statsapi.fetchScoreboard(MLB, SEP25).games());
        wm.getWireMock().removeStubMapping(earlier);
        espnDay(wm, SEP25, "espn/mlb/scoreboard-20260925-doubleheaders");
        save(provider.fetchScoreboard(MLB, SEP25).games());
        assertThat(links.count()).isPositive();

        games.deleteStartedBefore(Instant.parse("2026-09-27T00:00:00Z"));

        assertThat(links.count()).isZero();
    }

    @Test
    void whenBothFailTheStatsapiErrorIsReported(WireMockRuntimeInfo wm) {
        assertThatThrownBy(() -> provider.fetchScoreboard(MLB, SEP25))
                .isInstanceOfSatisfying(
                        ProviderException.class,
                        e -> {
                            assertThat(e.provider()).isEqualTo("mlb");
                            assertThat(e.status()).isEqualTo(406);
                            assertThat(e.getSuppressed()).hasSize(1);
                        });
    }
}
