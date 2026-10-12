package ca.hex42labs.sports.provider.espn;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.PeriodType;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.testing.Fixtures;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EspnMapperTest {

    private static final Instant FETCHED = Instant.parse("2026-10-12T18:00:00Z");

    private static EspnMapper.Scoreboard board(String league, String fixture) {
        return EspnMapper.scoreboard(TestLeagues.get(league), Fixtures.json(fixture), FETCHED);
    }

    private static Game game(EspnMapper.Scoreboard sb, String id) {
        return sb.games().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void nflScheduledWeekHasNullScoresDespiteUpstreamZeros() {
        var sb = board("nfl", "espn/nfl/scoreboard-week6-scheduled");
        assertThat(sb.games()).hasSize(14);
        assertThat(sb.games())
                .allSatisfy(
                        g -> {
                            assertThat(g.status()).isEqualTo(GameStatus.SCHEDULED);
                            assertThat(g.homeScore()).isNull();
                            assertThat(g.awayScore()).isNull();
                            assertThat(g.period()).isNull();
                            assertThat(g.periodType()).isNull();
                            assertThat(g.clock()).isNull();
                            assertThat(g.season()).isEqualTo("2026");
                            assertThat(g.seasonType()).isEqualTo("regular");
                            assertThat(g.fetchedAt()).isEqualTo(FETCHED);
                        });
        // Thursday night game: no TV "broadcasts", only the Prime Video geo broadcast.
        Game tnf = sb.games().get(0);
        assertThat(tnf.startsAt()).isEqualTo(Instant.parse("2026-10-16T00:15:00Z"));
        assertThat(tnf.broadcasts()).isNotEmpty();
        assertThat(tnf.statusDetail()).isEqualTo("10/15 - 8:15 PM EDT");
        assertThat(sb.teams()).hasSize(28);
    }

    @Test
    void nflOvertimeFinal() {
        Game g = game(board("nfl", "espn/nfl/scoreboard-20260913-final-ot"), "nfl:401872923");
        assertThat(g.status()).isEqualTo(GameStatus.FINAL);
        assertThat(g.homeTeamId()).isEqualTo("nfl:det");
        assertThat(g.awayTeamId()).isEqualTo("nfl:no");
        assertThat(g.homeScore()).isEqualTo(31);
        assertThat(g.awayScore()).isEqualTo(30);
        assertThat(g.period()).isEqualTo(5);
        assertThat(g.periodType()).isEqualTo(PeriodType.OVERTIME);
        assertThat(g.statusDetail()).isEqualTo("Final/OT");
        assertThat(g.venue()).isEqualTo("Ford Field");
        assertThat(g.broadcasts()).containsExactly("Fox");
        assertThat(g.clock()).isNull();
        assertThat(g.situation()).isNull();
    }

    @Test
    void regulationFinalsAreRegulation() {
        var sb = board("nfl", "espn/nfl/scoreboard-20260913-final-ot");
        assertThat(sb.games())
                .filteredOn(g -> "Final".equals(g.statusDetail()))
                .isNotEmpty()
                .allSatisfy(g -> assertThat(g.periodType()).isEqualTo(PeriodType.REGULATION));
    }

    @Test
    void collegeMultiOvertimeUsesNumericTeamIdsAndNcaaLogos() {
        var sb = board("ncaaf", "espn/ncaaf/scoreboard-20260912-multi-ot");
        assertThat(sb.games()).hasSize(80);
        Game fourOt =
                sb.games().stream()
                        .filter(g -> "Final/4OT".equals(g.statusDetail()))
                        .findFirst()
                        .orElseThrow();
        assertThat(fourOt.periodType()).isEqualTo(PeriodType.OVERTIME);
        assertThat(fourOt.homeTeamId()).matches("ncaaf:\\d+");
        Team any = sb.teams().get(0);
        assertThat(any.logoUrl())
                .matches("https://a\\.espncdn\\.com/i/teamlogos/ncaa/500/\\d+\\.png");
    }

    @Test
    void collegeBasketballPostponedIsNotFinal() {
        Game g = game(board("ncaab", "espn/ncaab/scoreboard-20260214-d1"), "ncaab:401828693");
        assertThat(g.status()).isEqualTo(GameStatus.POSTPONED);
        assertThat(g.homeScore()).isNull();
        assertThat(g.awayScore()).isNull();
        assertThat(g.periodType()).isNull();
        assertThat(g.statusDetail()).isEqualTo("Postponed");
    }

    @Test
    void collegeBasketballDivisionOneScoreboardIsComplete() {
        assertThat(board("ncaab", "espn/ncaab/scoreboard-20260214-d1").games()).hasSize(132);
        assertThat(board("ncaab", "espn/ncaab/scoreboard-20260214-final").games()).hasSize(17);
    }

    @ParameterizedTest
    @CsvSource({
        "epl,        espn/epl/scoreboard-20260919-full-time",
        "laliga,     espn/laliga/scoreboard-20260920",
        "bundesliga, espn/bundesliga/scoreboard-20260919",
    })
    void soccerFullTime(String league, String fixture) {
        var sb = board(league, fixture);
        assertThat(sb.games()).hasSize(5);
        assertThat(sb.games())
                .allSatisfy(
                        g -> {
                            assertThat(g.status()).isEqualTo(GameStatus.FINAL);
                            assertThat(g.statusDetail()).isEqualTo("FT");
                            assertThat(g.periodType()).isEqualTo(PeriodType.REGULATION);
                            assertThat(g.homeScore()).isNotNull();
                            assertThat(g.homeShootout()).isNull();
                            assertThat(g.homeTeamId()).matches(league + ":\\d+");
                        });
        assertThat(sb.teams())
                .allSatisfy(t -> assertThat(t.logoUrl()).contains("/teamlogos/soccer/500/"));
    }

    @Test
    void nbaScheduledAndFinal() {
        var scheduled = board("nba", "espn/nba/scoreboard-20261021-scheduled");
        assertThat(scheduled.games())
                .hasSize(11)
                .allSatisfy(g -> assertThat(g.homeScore()).isNull());
        var finals = board("nba", "espn/nba/scoreboard-20260310-final");
        assertThat(finals.games())
                .hasSize(11)
                .allSatisfy(g -> assertThat(g.homeScore()).isPositive());
        assertThat(finals.games())
                .filteredOn(g -> "Final/OT".equals(g.statusDetail()))
                .allSatisfy(g -> assertThat(g.periodType()).isEqualTo(PeriodType.OVERTIME));
    }

    @ParameterizedTest
    @CsvSource({
        "nfl, espn/nfl/scoreboard-20260715-offseason",
        "nba, espn/nba/scoreboard-20260801-offseason"
    })
    void offseasonIsEmptyNotAnError(String league, String fixture) {
        var sb = board(league, fixture);
        assertThat(sb.games()).isEmpty();
        assertThat(sb.teams()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "nfl,        espn/nfl/teams,        32, nfl:gb",
        "nba,        espn/nba/teams,        30, nba:tor",
        "ncaaf,      espn/ncaaf/teams,      762, ncaaf:145",
        "ncaab,      espn/ncaab/teams,      362, ncaab:2569",
        "epl,        espn/epl/teams,        20, epl:359",
        "laliga,     espn/laliga/teams,     20, laliga:83",
        "bundesliga, espn/bundesliga/teams, 18, bundesliga:124",
    })
    void teamLists(String league, String fixture, int count, String sampleId) {
        List<Team> teams = EspnMapper.teams(TestLeagues.get(league), Fixtures.json(fixture));
        assertThat(teams).hasSize(count);
        assertThat(teams).extracting(Team::id).contains(sampleId).doesNotHaveDuplicates();
        assertThat(teams).allSatisfy(t -> assertThat(t.name()).isNotBlank());
    }

    @Test
    void proTeamFields() {
        Team gb =
                EspnMapper.teams(TestLeagues.get("nfl"), Fixtures.json("espn/nfl/teams")).stream()
                        .filter(t -> t.id().equals("nfl:gb"))
                        .findFirst()
                        .orElseThrow();
        assertThat(gb.name()).isEqualTo("Green Bay Packers");
        assertThat(gb.shortName()).isEqualTo("Packers");
        assertThat(gb.abbreviation()).isEqualTo("GB");
        assertThat(gb.location()).isEqualTo("Green Bay");
        assertThat(gb.providerTeamId()).isEqualTo("9");
        assertThat(gb.logoUrl()).isEqualTo("https://a.espncdn.com/i/teamlogos/nfl/500/gb.png");
    }

    @Test
    void liveFootballSituationAndClock() {
        // Synthesised from a scheduled game until a real live capture lands (see
        // upstream-contract.md).
        ObjectNode ev =
                Fixtures.json("espn/nfl/scoreboard-week6-scheduled")
                        .path("events")
                        .path(0)
                        .deepCopy();
        ObjectNode status = (ObjectNode) ev.path("status");
        status.put("period", 3).put("displayClock", "5:42");
        ((ObjectNode) status.path("type"))
                .put("name", "STATUS_IN_PROGRESS")
                .put("state", "in")
                .put("shortDetail", "5:42 - 3rd");
        ObjectNode comp = (ObjectNode) ev.path("competitions").path(0);
        String homeId = comp.path("competitors").path(0).path("id").asText();
        comp.putObject("situation")
                .put("possession", homeId)
                .put("shortDownDistanceText", "2nd & 13")
                .put("possessionText", "DET 31")
                .put("isRedZone", false);
        ((ObjectNode) comp.path("competitors").path(0)).put("score", "14");
        ((ObjectNode) comp.path("competitors").path(1)).put("score", "10");

        Map<String, Team> seen = new HashMap<>();
        Game g = EspnMapper.event(TestLeagues.get("nfl"), ev, FETCHED, seen).orElseThrow();
        assertThat(g.status()).isEqualTo(GameStatus.LIVE);
        assertThat(g.clock()).isEqualTo("5:42");
        assertThat(g.period()).isEqualTo(3);
        assertThat(g.periodType()).isEqualTo(PeriodType.REGULATION);
        assertThat(g.situation()).isNotNull();
        assertThat(g.situation().downDistance()).isEqualTo("2nd & 13");
        assertThat(g.situation().possessionTeamId())
                .isIn(g.homeTeamId(), g.awayTeamId())
                .isNotNull();
        assertThat(seen).hasSize(2);
    }

    @Test
    void soccerPeriodTypes() {
        League epl = TestLeagues.get("epl");
        assertThat(EspnMapper.periodType(epl, GameStatus.LIVE, "STATUS_IN_PROGRESS", 2, null))
                .isEqualTo(PeriodType.REGULATION);
        assertThat(EspnMapper.periodType(epl, GameStatus.LIVE, "STATUS_IN_PROGRESS", 3, null))
                .isEqualTo(PeriodType.EXTRA_TIME);
        assertThat(EspnMapper.periodType(epl, GameStatus.FINAL, "STATUS_FINAL_AET", 4, "AET"))
                .isEqualTo(PeriodType.EXTRA_TIME);
        assertThat(EspnMapper.periodType(epl, GameStatus.FINAL, "STATUS_FINAL_PEN", 5, "FT-Pens"))
                .isEqualTo(PeriodType.PENALTIES);
    }

    @Test
    void malformedEventsAreSkippedNotFatal() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        var events = root.putArray("events");
        events.addObject().put("id", "1"); // no competitions
        ObjectNode good =
                Fixtures.json("espn/nfl/scoreboard-20260913-final-ot")
                        .path("events")
                        .path(0)
                        .deepCopy();
        events.add(good);
        ObjectNode noDate = good.deepCopy();
        noDate.remove("date");
        ((ObjectNode) noDate.path("competitions").path(0)).remove("date");
        noDate.put("id", "2");
        events.add(noDate);
        assertThat(EspnMapper.scoreboard(TestLeagues.get("nfl"), root, FETCHED).games()).hasSize(1);
    }

    @Test
    void scoreParsingIsTolerant() {
        ObjectMapper om = new ObjectMapper();
        assertThat(EspnMapper.score(om.valueToTree("21"))).isEqualTo(21);
        assertThat(EspnMapper.score(om.valueToTree(7))).isEqualTo(7);
        assertThat(EspnMapper.score(om.valueToTree(""))).isNull();
        assertThat(EspnMapper.score(om.valueToTree("--"))).isNull();
        assertThat(EspnMapper.score(om.valueToTree(Map.of("value", 3.0)))).isEqualTo(3);
        assertThat(EspnMapper.score(null)).isNull();
    }

    @Test
    void parsesMinutePrecisionDates() {
        assertThat(EspnMapper.parseInstant("2026-10-13T00:15Z"))
                .isEqualTo(Instant.parse("2026-10-13T00:15:00Z"));
        assertThat(EspnMapper.parseInstant("garbage")).isNull();
    }
}
