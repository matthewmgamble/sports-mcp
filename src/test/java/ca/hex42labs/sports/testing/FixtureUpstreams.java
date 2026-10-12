package ca.hex42labs.sports.testing;

import static ca.hex42labs.sports.testing.WireMockFixtures.gz;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** All three upstreams faked with recorded fixtures, for full-app tests. */
public final class FixtureUpstreams {

    private FixtureUpstreams() {}

    static final Map<String, String> ESPN_PATHS =
            Map.of(
                    "football/nfl", "nfl",
                    "basketball/nba", "nba",
                    "football/college-football", "ncaaf",
                    "basketball/mens-college-basketball", "ncaab",
                    "soccer/eng.1", "epl",
                    "soccer/esp.1", "laliga",
                    "soccer/ger.1", "bundesliga");

    static final Map<String, String> ESPN_SCOREBOARD =
            Map.of(
                    "nfl", "espn/nfl/scoreboard-week6-scheduled",
                    "nba", "espn/nba/scoreboard-20261021-scheduled",
                    "ncaaf", "espn/ncaaf/scoreboard-20260912-fbs",
                    "ncaab", "espn/ncaab/scoreboard-20260214-d1",
                    "epl", "espn/epl/scoreboard-20260919-full-time",
                    "laliga", "espn/laliga/scoreboard-20260920",
                    "bundesliga", "espn/bundesliga/scoreboard-20260919");

    public static void stub(WireMockRuntimeInfo wm) {
        WireMock mock = wm.getWireMock();
        ESPN_PATHS.forEach(
                (path, league) -> {
                    mock.register(
                            get(urlPathEqualTo("/espn/" + path + "/scoreboard"))
                                    .willReturn(gz(ESPN_SCOREBOARD.get(league))));
                    mock.register(
                            get(urlPathEqualTo("/espn/" + path + "/teams"))
                                    .willReturn(gz("espn/" + league + "/teams")));
                });
        mock.register(
                get(urlPathMatching("/espn/football/nfl/teams/[^/]+/schedule"))
                        .willReturn(gz("espn/nfl/team-schedule-gb")));
        mock.register(
                get(urlPathEqualTo("/nhl/standings/now")).willReturn(gz("nhl/standings-now")));
        mock.register(
                get(urlPathMatching("/nhl/schedule/.*"))
                        .willReturn(gz("nhl/schedule-20261013-week")));
        mock.register(
                get(urlPathMatching("/nhl/score/.*")).willReturn(gz("nhl/score-20261010-ot-so")));
        mock.register(
                get(urlPathMatching("/nhl/club-schedule-season/[A-Z]+/now"))
                        .willReturn(gz("nhl/club-schedule-season-tor")));
        mock.register(get(urlPathEqualTo("/mlb/teams")).willReturn(gz("mlb/teams")));
        mock.register(
                get(urlPathEqualTo("/mlb/schedule"))
                        .atPriority(1)
                        .withQueryParam("teamId", matching(".+"))
                        .willReturn(gz("mlb/team-schedule-tor-2026")));
        mock.register(
                get(urlPathEqualTo("/mlb/schedule"))
                        .atPriority(5)
                        .willReturn(gz("mlb/schedule-range-postseason")));
    }

    /** App environment pointing every provider at WireMock. */
    public static Map<String, String> env(WireMockRuntimeInfo wm, Path db) {
        Map<String, String> env = new HashMap<>();
        env.put("PORT", "0");
        env.put("MGMT_PORT", "0");
        env.put("DB_PATH", db.toString());
        env.put("BACKGROUND_SYNC", "false");
        // Some fixtures are months old (NCAAB is from Feb 2026); keep retention from deleting them.
        env.put("RETENTION_FINISHED_DAYS", "400");
        env.put("PROVIDER_ESPN_BASE_URL", wm.getHttpBaseUrl() + "/espn");
        env.put("PROVIDER_NHL_BASE_URL", wm.getHttpBaseUrl() + "/nhl");
        env.put("PROVIDER_MLB_BASE_URL", wm.getHttpBaseUrl() + "/mlb");
        return env;
    }
}
