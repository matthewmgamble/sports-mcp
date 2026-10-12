package ca.hex42labs.sports.provider.mlb;

import static ca.hex42labs.sports.testing.WireMockFixtures.gz;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.PeriodType;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.testing.Fixtures;
import ca.hex42labs.sports.testing.Games;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@WireMockTest
class MlbProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");

    private static MlbProvider provider(WireMockRuntimeInfo wm) {
        return new MlbProvider(
                UpstreamHttp.withDefaults(new ObjectMapper(), "test"),
                wm.getHttpBaseUrl(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ZERO);
    }

    /** Maps every game in a schedule fixture. */
    private static List<Game> games(String fixture) {
        List<Game> out = new ArrayList<>();
        for (JsonNode d : Fixtures.json(fixture).path("dates")) {
            for (JsonNode g : d.path("games")) {
                MlbMapper.game(g, NOW, new HashMap<>()).ifPresent(out::add);
            }
        }
        return out;
    }

    private static Game byId(List<Game> games, String id) {
        return games.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    // ---- teams ----

    @Test
    void mapsTeamsToCanonicalIds() {
        List<Team> teams = MlbProvider.teams(Fixtures.json("mlb/teams"));
        assertThat(teams).hasSize(30);
        Team tor = teams.stream().filter(t -> t.id().equals("mlb:tor")).findFirst().orElseThrow();
        assertThat(tor.name()).isEqualTo("Toronto Blue Jays");
        assertThat(tor.shortName()).isEqualTo("Blue Jays");
        assertThat(tor.providerTeamId()).isEqualTo("141");
        assertThat(tor.logoUrl()).isEqualTo("https://a.espncdn.com/i/teamlogos/mlb/500/tor.png");
        // MLB says AZ / CWS; ESPN (and our ids) say ari / chw.
        assertThat(teams).extracting(Team::id).contains("mlb:ari", "mlb:chw");
    }

    @Test
    void canonicalIdsMatchEspnAbbreviations() {
        Set<String> ours =
                MlbProvider.teams(Fixtures.json("mlb/teams")).stream()
                        .map(Team::id)
                        .collect(Collectors.toSet());
        JsonNode espn =
                Fixtures.json("espn/mlb/teams")
                        .path("sports")
                        .path(0)
                        .path("leagues")
                        .path(0)
                        .path("teams");
        Set<String> theirs =
                StreamSupport.stream(espn.spliterator(), false)
                        .map(
                                t ->
                                        "mlb:"
                                                + t.path("team")
                                                        .path("abbreviation")
                                                        .asText()
                                                        .toLowerCase())
                        .collect(Collectors.toSet());
        assertThat(ours).isEqualTo(theirs);
    }

    @Test
    void teamsFailureFallsBackToSeed(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(get(urlPathEqualTo("/teams")).willReturn(aResponse().withStatus(500)));
        assertThat(provider(wm).fetchTeams(TestLeagues.get("mlb"))).hasSize(30);
    }

    @Test
    void teamIdLookup() {
        assertThat(MlbProvider.teamId(141)).contains("mlb:tor");
        assertThat(MlbProvider.teamId(9999)).isEmpty();
    }

    // ---- games ----

    @Test
    void postponedGameIsNotFinalDespiteAbstractFinal() {
        Game g = byId(games("mlb/schedule-20260922-postponed"), "mlb:824785");
        assertThat(g.status()).isEqualTo(GameStatus.POSTPONED);
        assertThat(g.homeScore()).isNull();
        assertThat(g.awayScore()).isNull();
        assertThat(g.statusDetail()).isEqualTo("Postponed: Rain");
        assertThat(g.awayTeamId()).isEqualTo("mlb:tor");
        assertThat(g.homeTeamId()).isEqualTo("mlb:bal");
        assertThat(g.venue()).isEqualTo("Oriole Park at Camden Yards");
    }

    @Test
    void cancelledGame() {
        Game g = byId(games("mlb/schedule-20260927-cancelled"), "mlb:823490");
        assertThat(g.status()).isEqualTo(GameStatus.CANCELLED);
        assertThat(g.homeScore()).isNull();
    }

    @Test
    void extraInningsFinal() {
        Game g = byId(games("mlb/schedule-20260902-extra-innings"), "mlb:824312");
        assertThat(g.status()).isEqualTo(GameStatus.FINAL);
        assertThat(g.period()).isEqualTo(11);
        assertThat(g.periodType()).isEqualTo(PeriodType.EXTRA_INNINGS);
        assertThat(g.statusDetail()).isEqualTo("Final/11");
        assertThat(g.homeScore()).isEqualTo(6);
        assertThat(g.awayScore()).isEqualTo(5);
        assertThat(g.season()).isEqualTo("2026");
        assertThat(g.seasonType()).isEqualTo("regular");
    }

    @Test
    void periodTypeFollowsInningsPlayed() {
        // 2026-09-02 had five extra-inning games (10, 10, 10, 11 and 12 innings).
        List<Game> all = games("mlb/schedule-20260902-extra-innings");
        assertThat(all)
                .filteredOn(g -> g.periodType() == PeriodType.EXTRA_INNINGS)
                .hasSize(5)
                .allSatisfy(g -> assertThat(g.statusDetail()).isEqualTo("Final/" + g.period()));
        assertThat(all)
                .filteredOn(g -> g.periodType() == PeriodType.REGULATION)
                .hasSize(10)
                .allSatisfy(g -> assertThat(g.period()).isLessThanOrEqualTo(9));
    }

    @Test
    void postseasonTypes() {
        assertThat(games("mlb/schedule-range-postseason"))
                .hasSize(18)
                .allSatisfy(g -> assertThat(g.seasonType()).isEqualTo("postseason"));
    }

    @Test
    void springTrainingIsExcluded() {
        ObjectNode g =
                Fixtures.json("mlb/schedule-20260902-extra-innings")
                        .path("dates")
                        .path(0)
                        .path("games")
                        .path(0)
                        .deepCopy();
        g.put("gameType", "S");
        assertThat(MlbMapper.game(g, NOW, new HashMap<>())).isEmpty();
    }

    @Test
    void liveGameShowsHalfInning() {
        ObjectNode g =
                Fixtures.json("mlb/schedule-20260902-extra-innings")
                        .path("dates")
                        .path(0)
                        .path("games")
                        .path(0)
                        .deepCopy();
        ObjectNode st = (ObjectNode) g.path("status");
        st.put("abstractGameState", "Live")
                .put("codedGameState", "I")
                .put("detailedState", "In Progress");
        ObjectNode ls = (ObjectNode) g.path("linescore");
        ls.put("currentInning", 7).put("currentInningOrdinal", "7th").put("inningState", "Top");
        Game live = MlbMapper.game(g, NOW, new HashMap<>()).orElseThrow();
        assertThat(live.status()).isEqualTo(GameStatus.LIVE);
        assertThat(live.statusDetail()).isEqualTo("Top 7th");
        assertThat(live.period()).isEqualTo(7);
        assertThat(live.homeScore()).isNotNull();

        st.put("detailedState", "Delayed: Rain");
        assertThat(MlbMapper.game(g, NOW, new HashMap<>()).orElseThrow().statusDetail())
                .isEqualTo("Delayed: Rain");
    }

    @ParameterizedTest
    @CsvSource({
        "D, Final,   POSTPONED",
        "C, Final,   CANCELLED",
        "T, Live,    SUSPENDED",
        "U, Final,   SUSPENDED",
        "S, Preview, SCHEDULED",
        "I, Live,    LIVE",
        "F, Final,   FINAL",
        "X, Other,   UNKNOWN",
    })
    void statusMapping(String coded, String abstractState, GameStatus expected) {
        assertThat(MlbMapper.status(coded, abstractState)).isEqualTo(expected);
    }

    // ---- schedule fetching ----

    @Test
    void rangeKeepsTheMakeupListingOfAPostponedGame(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .willReturn(gz("mlb/schedule-range-postponed-makeup")));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("mlb"),
                                LocalDate.of(2026, 9, 22),
                                LocalDate.of(2026, 9, 23));
        Game g = byId(r.games(), "mlb:824785");
        assertThat(g.status()).isEqualTo(GameStatus.FINAL);
        assertThat(g.startsAt()).isEqualTo(Instant.parse("2026-09-23T17:35:00Z"));
        assertThat(g.homeScore()).isEqualTo(4);
        assertThat(r.games()).extracting(Game::id).doesNotHaveDuplicates();
        wm.getWireMock()
                .verifyThat(
                        getRequestedFor(urlPathEqualTo("/schedule"))
                                .withQueryParam("startDate", equalTo("2026-09-22"))
                                .withQueryParam("endDate", equalTo("2026-09-23"))
                                .withQueryParam("hydrate", equalTo("linescore,team")));
    }

    @Test
    void range406FallsBackToSingleDays(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("startDate", equalTo("2026-09-22"))
                                .willReturn(aResponse().withStatus(406)));
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("date", equalTo("2026-09-22"))
                                .willReturn(gz("mlb/schedule-20260922-postponed")));
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("date", equalTo("2026-09-23"))
                                .willReturn(gz("mlb/schedule-20260923-makeup")));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("mlb"),
                                LocalDate.of(2026, 9, 22),
                                LocalDate.of(2026, 9, 23));
        assertThat(r.complete()).isTrue();
        assertThat(byId(r.games(), "mlb:824785").status()).isEqualTo(GameStatus.FINAL);
        assertThat(r.games().size()).isGreaterThan(20);
    }

    @Test
    void fallbackStopsAtTheFirstThrottledDay(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("startDate", equalTo("2026-09-22"))
                                .willReturn(aResponse().withStatus(406)));
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("date", equalTo("2026-09-22"))
                                .willReturn(gz("mlb/schedule-20260922-postponed")));
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("date", equalTo("2026-09-23"))
                                .willReturn(aResponse().withStatus(406)));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("mlb"),
                                LocalDate.of(2026, 9, 22),
                                LocalDate.of(2026, 9, 30));
        assertThat(r.complete()).isFalse();
        assertThat(r.games()).isNotEmpty(); // the 22nd still counts
        // Range + the 22nd + the throttled 23rd; the 24th..30th aren't attempted.
        assertThat(wm.getWireMock().find(getRequestedFor(urlPathEqualTo("/schedule")))).hasSize(3);
    }

    @Test
    void otherRangeErrorsPropagate(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(get(urlPathEqualTo("/schedule")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(
                        () ->
                                provider(wm)
                                        .fetchSchedule(
                                                TestLeagues.get("mlb"),
                                                LocalDate.of(2026, 9, 22),
                                                LocalDate.of(2026, 9, 23)))
                .isInstanceOfSatisfying(
                        ProviderException.class, e -> assertThat(e.status()).isEqualTo(500));
    }

    @Test
    void offseasonIsEmptyAndComplete(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .willReturn(gz("mlb/schedule-20270115-offseason")));
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("mlb"),
                                LocalDate.of(2027, 1, 14),
                                LocalDate.of(2027, 1, 29));
        assertThat(r.games()).isEmpty();
        assertThat(r.complete()).isTrue();
    }

    @Test
    void perGameLinescoreUpdatesALiveGame(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get("/game/824312/linescore")
                                .willReturn(gz("mlb/linescore-824312-extra-innings")));
        Game scheduleView =
                Games.live(
                        Games.scheduled(
                                "mlb:824312", "mlb:sea", "mlb:hou", NOW.minusSeconds(14_400), NOW),
                        5,
                        5,
                        null,
                        NOW.minusSeconds(60));
        scheduleView = withDetail(scheduleView, "Top 11th");
        Game fresh = provider(wm).refreshGame(TestLeagues.get("mlb"), scheduleView).orElseThrow();
        assertThat(fresh.homeScore()).isEqualTo(6);
        assertThat(fresh.awayScore()).isEqualTo(5);
        assertThat(fresh.period()).isEqualTo(11);
        assertThat(fresh.periodType()).isEqualTo(PeriodType.EXTRA_INNINGS);
        assertThat(fresh.statusDetail()).isEqualTo("Bot 11th");
        assertThat(fresh.fetchedAt()).isEqualTo(NOW);
        assertThat(fresh.status())
                .isEqualTo(GameStatus.LIVE); // status comes from the scoreboard, not here
    }

    @Test
    void perGameLinescoreKeepsDelayText() {
        Game delayed =
                withDetail(
                        Games.live(
                                Games.scheduled("mlb:824312", "mlb:sea", "mlb:hou", NOW, NOW),
                                1,
                                0,
                                null,
                                NOW),
                        "Delayed: Rain");
        Game fresh =
                MlbMapper.applyLinescore(
                        delayed, Fixtures.json("mlb/linescore-824312-extra-innings"), NOW);
        assertThat(fresh.statusDetail()).isEqualTo("Delayed: Rain");
        assertThat(fresh.homeScore()).isEqualTo(6);
    }

    @Test
    void perGameRefreshSkipsGamesNotLive(WireMockRuntimeInfo wm) throws Exception {
        Game fin =
                Games.rescheduled(
                        Games.scheduled("mlb:824312", "mlb:sea", "mlb:hou", NOW, NOW),
                        NOW,
                        GameStatus.FINAL,
                        NOW);
        assertThat(provider(wm).refreshGame(TestLeagues.get("mlb"), fin)).isEmpty();
        wm.getWireMock().verifyThat(0, getRequestedFor(urlPathEqualTo("/game/824312/linescore")));
        assertThat(provider(wm).hasPerGameFeed()).isTrue();
    }

    private static Game withDetail(Game g, String detail) {
        return new Game(
                g.id(),
                g.league(),
                "mlb",
                g.providerEventId(),
                g.season(),
                g.seasonType(),
                g.homeTeamId(),
                g.awayTeamId(),
                g.startsAt(),
                g.status(),
                detail,
                g.period(),
                g.periodType(),
                g.clock(),
                g.homeScore(),
                g.awayScore(),
                null,
                null,
                g.venue(),
                g.broadcasts(),
                null,
                g.fetchedAt(),
                null);
    }

    @Test
    void scoreboardIsOneDate(WireMockRuntimeInfo wm) throws Exception {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/schedule"))
                                .withQueryParam("date", equalTo("2026-09-02"))
                                .willReturn(gz("mlb/schedule-20260902-extra-innings")));
        var batch = provider(wm).fetchScoreboard(TestLeagues.get("mlb"), LocalDate.of(2026, 9, 2));
        assertThat(batch.games()).hasSize(15);
        assertThat(batch.teams()).hasSize(30);
        assertThat(Optional.of(batch.fetchedAt())).contains(NOW);
    }
}
