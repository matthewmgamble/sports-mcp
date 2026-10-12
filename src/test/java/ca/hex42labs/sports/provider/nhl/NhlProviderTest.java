package ca.hex42labs.sports.provider.nhl;

import static ca.hex42labs.sports.testing.WireMockFixtures.gz;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.PeriodType;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.testing.Fixtures;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@WireMockTest
class NhlProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");

    private static NhlProvider provider(WireMockRuntimeInfo wm) {
        return new NhlProvider(
                UpstreamHttp.withDefaults(new ObjectMapper(), "test"),
                wm.getHttpBaseUrl(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- teams ----

    @Test
    void mapsStandingsToCanonicalTeams() {
        List<Team> teams = NhlProvider.teamsFromStandings(Fixtures.json("nhl/standings-now"));
        assertThat(teams).hasSize(32);
        Team tor = teams.stream().filter(t -> t.id().equals("nhl:tor")).findFirst().orElseThrow();
        assertThat(tor.name()).isEqualTo("Toronto Maple Leafs");
        assertThat(tor.shortName()).isEqualTo("Maple Leafs");
        assertThat(tor.location()).isEqualTo("Toronto");
        assertThat(tor.providerTeamId()).isEqualTo("TOR");
        assertThat(tor.logoUrl()).isEqualTo("https://a.espncdn.com/i/teamlogos/nhl/500/tor.png");
        assertThat(teams)
                .extracting(Team::id)
                .contains("nhl:la", "nhl:sj", "nhl:nj", "nhl:tb", "nhl:utah");
    }

    @Test
    void canonicalIdsMatchEspnAbbreviations() {
        Set<String> ours =
                NhlProvider.teamsFromStandings(Fixtures.json("nhl/standings-now")).stream()
                        .map(Team::id)
                        .collect(Collectors.toSet());
        JsonNode teams =
                Fixtures.json("espn/nhl/teams")
                        .path("sports")
                        .path(0)
                        .path("leagues")
                        .path(0)
                        .path("teams");
        Set<String> espn =
                StreamSupport.stream(teams.spliterator(), false)
                        .map(
                                t ->
                                        "nhl:"
                                                + t.path("team")
                                                        .path("abbreviation")
                                                        .asText()
                                                        .toLowerCase())
                        .collect(Collectors.toSet());
        assertThat(ours).isEqualTo(espn);
    }

    @Test
    void emptyStandingsFallBackToSeed(WireMockRuntimeInfo wm) {
        wm.getWireMock().register(get("/standings/now").willReturn(okJson("{\"standings\":[]}")));
        assertThat(provider(wm).fetchTeams(TestLeagues.get("nhl"))).hasSize(32);
    }

    @Test
    void upstreamFailureFallsBackToSeed(WireMockRuntimeInfo wm) {
        wm.getWireMock().register(get("/standings/now").willReturn(aResponse().withStatus(503)));
        assertThat(provider(wm).fetchTeams(TestLeagues.get("nhl"))).hasSize(32);
    }

    // ---- games ----

    @Test
    void scoreboardMapsOvertimeAndShootoutFinals(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(get("/score/2026-10-10").willReturn(gz("nhl/score-20261010-ot-so")));
        ProviderBatch batch =
                provider(wm).fetchScoreboard(TestLeagues.get("nhl"), LocalDate.of(2026, 10, 10));
        assertThat(batch.games())
                .hasSize(14)
                .allSatisfy(g -> assertThat(g.status()).isEqualTo(GameStatus.FINAL));
        assertThat(batch.fetchedAt()).isEqualTo(NOW);

        Game so = byId(batch.games(), "nhl:2026020075");
        assertThat(so.homeTeamId()).isEqualTo("nhl:mtl");
        assertThat(so.awayTeamId()).isEqualTo("nhl:det");
        assertThat(so.homeScore()).isEqualTo(1);
        assertThat(so.awayScore()).isZero();
        assertThat(so.periodType()).isEqualTo(PeriodType.SHOOTOUT);
        assertThat(so.statusDetail()).isEqualTo("Final/SO");
        assertThat(so.venue()).isEqualTo("Centre Bell");
        assertThat(so.broadcasts()).contains("SNE", "TVAS");
        assertThat(so.season()).isEqualTo("2026-27");
        assertThat(so.seasonType()).isEqualTo("regular");
        assertThat(so.clock()).isNull();

        Game ot = byId(batch.games(), "nhl:2026020083");
        assertThat(ot.periodType()).isEqualTo(PeriodType.OVERTIME);
        assertThat(ot.statusDetail()).isEqualTo("Final/OT");
        assertThat(batch.games())
                .filteredOn(g -> g.periodType() == PeriodType.REGULATION)
                .hasSize(10)
                .allSatisfy(g -> assertThat(g.statusDetail()).isEqualTo("Final"));
    }

    @Test
    void scheduleWeekIsScheduledWithNullScores(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(get("/schedule/2026-10-13").willReturn(gz("nhl/schedule-20261013-week")));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("nhl"),
                                LocalDate.of(2026, 10, 13),
                                LocalDate.of(2026, 10, 19));
        assertThat(r.complete()).isTrue();
        assertThat(r.games()).hasSize(51);
        assertThat(r.games())
                .allSatisfy(
                        g -> {
                            assertThat(g.status()).isEqualTo(GameStatus.SCHEDULED);
                            assertThat(g.homeScore()).isNull();
                            assertThat(g.period()).isNull();
                            assertThat(g.statusDetail()).isEqualTo("Scheduled");
                        });
        assertThat(r.teams()).extracting(Team::id).contains("nhl:det");
        Team det =
                r.teams().stream().filter(t -> t.id().equals("nhl:det")).findFirst().orElseThrow();
        assertThat(det.name()).isEqualTo("Detroit Red Wings");
    }

    @Test
    void schedulePagesWeeklyAndTrimsToWindow(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(get("/schedule/2026-10-11").willReturn(gz("nhl/schedule-20261013-week")));
        wm.getWireMock()
                .register(
                        get("/schedule/2026-10-20")
                                .willReturn(
                                        okJson(
                                                "{\"gameWeek\":[],\"nextStartDate\":\"2026-10-27\"}")));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("nhl"),
                                LocalDate.of(2026, 10, 11),
                                LocalDate.of(2026, 10, 15));
        // Fixture week is Oct 13-19; window ends Oct 15: 16 + 2 + 11 games.
        assertThat(r.games()).hasSize(29);
        assertThat(r.complete()).isTrue();
        wm.getWireMock().verifyThat(1, getRequestedFor(urlPathMatching("/schedule/.*")));

        r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("nhl"),
                                LocalDate.of(2026, 10, 11),
                                LocalDate.of(2026, 10, 26));
        assertThat(r.games()).hasSize(51);
        wm.getWireMock().verifyThat(3, getRequestedFor(urlPathMatching("/schedule/.*")));
    }

    @Test
    void offseasonScheduleIsEmptyAndComplete(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get("/schedule/2026-07-14")
                                .willReturn(gz("nhl/schedule-20260715-offseason")));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("nhl"),
                                LocalDate.of(2026, 7, 14),
                                LocalDate.of(2026, 7, 30));
        assertThat(r.games()).isEmpty();
        assertThat(r.complete()).isTrue();
        // nextStartDate (Sep 16) is past the window: one request.
        wm.getWireMock().verifyThat(1, getRequestedFor(urlPathMatching("/schedule/.*")));
    }

    @Test
    void failedPageKeepsTheRest(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(get("/schedule/2026-10-11").willReturn(gz("nhl/schedule-20261013-week")));
        wm.getWireMock()
                .register(get("/schedule/2026-10-20").willReturn(aResponse().withStatus(503)));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("nhl"),
                                LocalDate.of(2026, 10, 11),
                                LocalDate.of(2026, 10, 26));
        assertThat(r.games()).hasSize(51);
        assertThat(r.failures()).singleElement().asString().contains("2026-10-20").contains("503");
    }

    @Test
    void liveGameHasClockAndPeriodText() {
        ObjectNode g = Fixtures.json("nhl/score-20261010-ot-so").path("games").path(0).deepCopy();
        g.put("gameState", "LIVE");
        g.remove("gameOutcome");
        ((ObjectNode) g.path("periodDescriptor")).put("number", 2).put("periodType", "REG");
        ((ObjectNode) g.path("clock")).put("timeRemaining", "12:34").put("inIntermission", false);
        Game live = NhlMapper.game(g, NOW, new HashMap<>()).orElseThrow();
        assertThat(live.status()).isEqualTo(GameStatus.LIVE);
        assertThat(live.clock()).isEqualTo("12:34");
        assertThat(live.period()).isEqualTo(2);
        assertThat(live.statusDetail()).isEqualTo("2nd 12:34");
        assertThat(live.homeScore()).isNotNull();

        ((ObjectNode) g.path("clock")).put("inIntermission", true);
        Game intermission = NhlMapper.game(g, NOW, new HashMap<>()).orElseThrow();
        assertThat(intermission.statusDetail()).isEqualTo("2nd Intermission");
        assertThat(intermission.clock()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "FUT,   OK,   SCHEDULED",
        "PRE,   OK,   SCHEDULED",
        "LIVE,  OK,   LIVE",
        "CRIT,  OK,   LIVE",
        "FINAL, OK,   FINAL",
        "OFF,   OK,   FINAL",
        "FUT,   PPD,  POSTPONED",
        "LIVE,  SUSP, SUSPENDED",
        "FUT,   CNCL, CANCELLED",
        "WAT,   OK,   UNKNOWN",
    })
    void statusMapping(String gameState, String scheduleState, GameStatus expected) {
        assertThat(NhlMapper.status(gameState, scheduleState)).isEqualTo(expected);
    }

    @Test
    void playoffOvertimeNames() {
        assertThat(NhlMapper.periodName(4, PeriodType.OVERTIME)).isEqualTo("OT");
        assertThat(NhlMapper.periodName(5, PeriodType.OVERTIME)).isEqualTo("2OT");
        assertThat(NhlMapper.periodName(5, PeriodType.SHOOTOUT)).isEqualTo("SO");
    }

    private static Game byId(List<Game> games, String id) {
        return games.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void playoffGamesCarryTheirRoundAndSeries() {
        ObjectNode s = new ObjectMapper().createObjectNode();
        s.put("round", 1)
                .put("seriesTitle", "1st Round")
                .put("neededToWin", 4)
                .put("topSeedTeamAbbrev", "TOR")
                .put("topSeedWins", 2)
                .put("bottomSeedTeamAbbrev", "OTT")
                .put("bottomSeedWins", 1)
                .put("gameNumberOfSeries", 4);
        assertThat(NhlMapper.round(s)).isEqualTo("1st Round Game 4");
        assertThat(NhlMapper.series(s)).isEqualTo("TOR leads 2-1");
        s.put("bottomSeedWins", 4).remove("seriesTitle");
        s.put("round", 4);
        assertThat(NhlMapper.round(s)).isEqualTo("Stanley Cup Final Game 4");
        assertThat(NhlMapper.series(s)).isEqualTo("OTT wins 4-2");
        s.put("topSeedWins", 0).put("bottomSeedWins", 0);
        assertThat(NhlMapper.series(s)).isNull();
        // A regular-season game has neither.
        Game regular =
                NhlMapper.game(
                                Fixtures.json("nhl/score-20261006-ot").path("games").path(0),
                                NOW,
                                new HashMap<>())
                        .orElseThrow();
        assertThat(regular.round()).isNull();
        assertThat(regular.series()).isNull();
        assertThat(regular.startTimeTbd()).isFalse();
    }
}
