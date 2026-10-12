package ca.hex42labs.sports;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.api.Schemas;
import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.testing.FixtureUpstreams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sprint 4 acceptance: REST and MCP return the same envelope for the same query, responses conform
 * to the published schemas, and bad input gets the same actionable error on both.
 */
class ApiContractTest {

    /** 10:00 Eastern, Monday Oct 12. */
    private static final Instant NOW = Instant.parse("2026-10-12T14:00:00Z");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @RegisterExtension
    static WireMockExtension upstream =
            WireMockExtension.newInstance()
                    .options(
                            com.github.tomakehurst.wiremock.core.WireMockConfiguration
                                    .wireMockConfig()
                                    .dynamicPort())
                    .resetOnEachTest(false) // stubs are registered once in @BeforeAll
                    .build();

    @TempDir static Path tmp;
    private static App app;
    private static String base;

    @BeforeAll
    static void start() {
        FixtureUpstreams.stub(upstream.getRuntimeInfo());
        app =
                new App(
                                AppConfig.fromMap(
                                        FixtureUpstreams.env(
                                                upstream.getRuntimeInfo(), tmp.resolve("api.db"))),
                                Clock.fixed(NOW, ZoneOffset.UTC))
                        .start();
        base = "http://127.0.0.1:" + app.port();
        // Populate like the background jobs would.
        app.leagues()
                .enabled()
                .forEach(
                        l -> {
                            app.teams().sync(l);
                            app.schedules().full(l, true);
                        });
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    // ---------------------------------------------------------------- equivalence

    static Stream<Arguments> equivalentQueries() {
        return Stream.of(
                Arguments.of("/api/v1/leagues", "sports_list_leagues", Map.of(), "list:League"),
                Arguments.of(
                        "/api/v1/teams?search=leafs",
                        "sports_list_teams",
                        Map.of("search", "leafs"),
                        "list:Team"),
                Arguments.of(
                        "/api/v1/leagues/nhl/teams",
                        "sports_list_teams",
                        Map.of("league", "nhl"),
                        "list:Team"),
                Arguments.of(
                        "/api/v1/games?league=nhl&date=2026-10-13",
                        "sports_get_games",
                        Map.of("league", "nhl", "date", "2026-10-13"),
                        "list:Game"),
                Arguments.of(
                        "/api/v1/games?league=nhl&from=2026-10-13&to=2026-10-19&limit=100&tz=America/Vancouver",
                        "sports_get_games",
                        Map.of(
                                "league",
                                "nhl",
                                "from",
                                "2026-10-13",
                                "to",
                                "2026-10-19",
                                "limit",
                                100,
                                "timezone",
                                "America/Vancouver"),
                        "list:Game"),
                Arguments.of(
                        "/api/v1/games?team=leafs&from=2026-10-01&to=2026-10-31",
                        "sports_get_games",
                        Map.of("team", "leafs", "from", "2026-10-01", "to", "2026-10-31"),
                        "list:Game"),
                Arguments.of(
                        "/api/v1/games?league=nfl&date=tomorrow&status=SCHEDULED",
                        "sports_get_games",
                        Map.of("league", "nfl", "date", "tomorrow", "status", "SCHEDULED"),
                        "list:Game"),
                Arguments.of("/api/v1/games/live", "sports_get_live_scores", Map.of(), "list:Game"),
                Arguments.of(
                        "/api/v1/games/nhl:2026020090",
                        "sports_get_game",
                        Map.of("gameId", "nhl:2026020090"),
                        "one:Game"),
                Arguments.of(
                        "/api/v1/teams/nhl:tor/schedule",
                        "sports_get_team_schedule",
                        Map.of("team", "nhl:tor"),
                        "list:Game"),
                Arguments.of(
                        "/api/v1/teams/kings/schedule?league=nhl",
                        "sports_get_team_schedule",
                        Map.of("team", "kings", "league", "nhl"),
                        "list:Game"),
                Arguments.of(
                        "/api/v1/teams/" + enc("Green Bay Packers") + "/schedule",
                        "sports_get_team_schedule",
                        Map.of("team", "Green Bay Packers"),
                        "list:Game"),
                Arguments.of(
                        "/api/v1/teams/"
                                + enc("Blue Jays")
                                + "/schedule?from=2026-09-01&to=2026-09-30&limit=50",
                        "sports_get_team_schedule",
                        Map.of(
                                "team",
                                "Blue Jays",
                                "from",
                                "2026-09-01",
                                "to",
                                "2026-09-30",
                                "limit",
                                50),
                        "list:Game"));
    }

    @ParameterizedTest(name = "{1} == GET {0}")
    @MethodSource("equivalentQueries")
    void restAndMcpReturnTheSameEnvelope(
            String path, String tool, Map<String, Object> args, String shape) throws Exception {
        HttpResponse<String> rest = get(path);
        assertThat(rest.statusCode()).as(rest.body()).isEqualTo(200);
        JsonNode restBody = JSON.readTree(rest.body());

        JsonNode call = mcp("tools/call", Map.of("name", tool, "arguments", args));
        assertThat(call.at("/result/isError").asBoolean(false)).as(call.toString()).isFalse();
        JsonNode structured = call.at("/result/structuredContent");

        assertThat(structured).isEqualTo(restBody);
        assertThat(restBody.at("/meta/isStale").asBoolean())
                .as("upstream fixtures served fresh data")
                .isFalse();
        assertThat(call.at("/result/content/0/text").asText()).isNotBlank();
        assertConforms(shape, restBody);
    }

    @Test
    void responsesCarryRealData() throws Exception {
        JsonNode day = JSON.readTree(get("/api/v1/games?league=nhl&date=2026-10-13").body());
        assertThat(day.at("/meta/count").asInt()).isEqualTo(16);
        assertThat(day.at("/meta/sources/0").asText()).isEqualTo("nhl");
        assertThat(day.at("/meta/attribution").asText()).contains("NHL");
        assertThat(day.at("/meta/timezone").asText()).isEqualTo("America/New_York");
        JsonNode first = day.at("/data/0");
        assertThat(first.at("/status").asText()).isEqualTo("SCHEDULED");
        assertThat(first.has("clock")).isFalse();
        assertThat(first.at("/home/score").isMissingNode()).isTrue();
        assertThat(first.at("/home/name").asText())
                .contains(" "); // full catalog name, not just "Red Wings"
        assertThat(first.at("/localStart").asText()).endsWith("-04:00");

        JsonNode vancouver =
                JSON.readTree(
                        get("/api/v1/games?league=nhl&date=2026-10-13&tz=America/Vancouver")
                                .body());
        assertThat(vancouver.at("/data/0/localStart").asText()).endsWith("-07:00");

        JsonNode leafs = JSON.readTree(get("/api/v1/teams/leafs/schedule").body());
        assertThat(leafs.at("/meta/count").asInt()).isEqualTo(8); // last 3 + next 5
        List<String> starts = new ArrayList<>();
        leafs.at("/data").forEach(g -> starts.add(g.at("/startsAt").asText()));
        assertThat(starts).isSorted();
        assertThat(leafs.at("/data/0/status").asText()).isEqualTo("FINAL");
        assertThat(leafs.at("/data/0/home/score").isInt()).isTrue();
        assertThat(leafs.at("/data/7/status").asText()).isEqualTo("SCHEDULED");

        JsonNode packers = JSON.readTree(get("/api/v1/teams/nfl:gb/schedule").body());
        // Weekly sport: next 5 games reach well past the 14-day sync window.
        assertThat(packers.at("/data/7/startsAt").asText()).isGreaterThan("2026-11-01");
    }

    @Test
    void toolListIsCompleteAndAnnotated() throws Exception {
        JsonNode tools = mcp("tools/list", Map.of()).at("/result/tools");
        List<String> names = new ArrayList<>();
        tools.forEach(
                t -> {
                    names.add(t.at("/name").asText());
                    assertThat(t.at("/annotations/readOnlyHint").asBoolean()).isTrue();
                    assertThat(t.at("/outputSchema/properties/meta").isObject()).isTrue();
                    assertThat(t.at("/description").asText()).hasSizeGreaterThan(30);
                    assertThat(t.toString()).doesNotContain("$ref"); // inlined for clients
                });
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "sports_list_leagues",
                        "sports_list_teams",
                        "sports_get_games",
                        "sports_get_live_scores",
                        "sports_get_game",
                        "sports_get_team_schedule");
    }

    // ---------------------------------------------------------------- errors

    static Stream<Arguments> badInputs() {
        return Stream.of(
                Arguments.of(
                        "/api/v1/games?league=nlf",
                        "sports_get_games",
                        Map.of("league", "nlf"),
                        400,
                        "invalid_argument",
                        "nfl"),
                Arguments.of(
                        "/api/v1/games",
                        "sports_get_games",
                        Map.of(),
                        400,
                        "invalid_argument",
                        "league or a team"),
                Arguments.of(
                        "/api/v1/games?team=toronto",
                        "sports_get_games",
                        Map.of("team", "toronto"),
                        400,
                        "ambiguous_team",
                        "nhl:tor"),
                Arguments.of(
                        "/api/v1/games?team=quidditch",
                        "sports_get_games",
                        Map.of("team", "quidditch"),
                        404,
                        "team_not_found",
                        "sports_list_teams"),
                Arguments.of(
                        "/api/v1/games?league=nhl&date=next+week",
                        "sports_get_games",
                        Map.of("league", "nhl", "date", "next week"),
                        400,
                        "invalid_argument",
                        "YYYY-MM-DD"),
                Arguments.of(
                        "/api/v1/games?league=nhl&from=2026-10-01&to=2026-12-31",
                        "sports_get_games",
                        Map.of("league", "nhl", "from", "2026-10-01", "to", "2026-12-31"),
                        400,
                        "invalid_argument",
                        "31 days"),
                Arguments.of(
                        "/api/v1/games?league=nhl&tz=Mars/Olympus",
                        "sports_get_games",
                        Map.of("league", "nhl", "timezone", "Mars/Olympus"),
                        400,
                        "invalid_argument",
                        "IANA"),
                Arguments.of(
                        "/api/v1/games?league=nhl&status=winning",
                        "sports_get_games",
                        null,
                        400,
                        "invalid_argument",
                        "status"),
                Arguments.of(
                        "/api/v1/games/nfl:999",
                        "sports_get_game",
                        Map.of("gameId", "nfl:999"),
                        404,
                        "game_not_found",
                        "nfl:999"),
                Arguments.of(
                        "/api/v1/games/garbage",
                        "sports_get_game",
                        Map.of("gameId", "garbage"),
                        400,
                        "invalid_argument",
                        "nfl:401872994"),
                Arguments.of(
                        "/api/v1/teams/leafs/schedule?from=2026-10-20&to=2026-10-01",
                        "sports_get_team_schedule",
                        Map.of("team", "leafs", "from", "2026-10-20", "to", "2026-10-01"),
                        400,
                        "invalid_argument",
                        "before"),
                Arguments.of(
                        "/api/v1/teams",
                        "sports_list_teams",
                        Map.of(),
                        400,
                        "invalid_argument",
                        "search"));
    }

    @ParameterizedTest(name = "GET {0} -> {3} {4}")
    @MethodSource("badInputs")
    void badInputGetsTheSameActionableErrorOnBoth(
            String path,
            String tool,
            Map<String, Object> args,
            int status,
            String code,
            String hint)
            throws Exception {
        HttpResponse<String> rest = get(path);
        assertThat(rest.statusCode()).as(rest.body()).isEqualTo(status);
        JsonNode body = JSON.readTree(rest.body());
        assertThat(body.at("/error/code").asText()).isEqualTo(code);
        assertThat(body.at("/error/message").asText()).contains(hint);
        assertConformsTo(Schemas.bundled().inline(Map.of("$ref", "#/$defs/Error")), body);

        if (args != null) { // (status=winning is rejected by the MCP input schema's enum first)
            JsonNode call = mcp("tools/call", Map.of("name", tool, "arguments", args));
            assertThat(call.at("/result/isError").asBoolean()).as(call.toString()).isTrue();
            assertThat(call.at("/result/content/0/text").asText())
                    .startsWith(code + ":")
                    .contains(hint);
        }
    }

    @Test
    void ambiguousTeamListsCandidatesInDetails() throws Exception {
        JsonNode body = JSON.readTree(get("/api/v1/games?team=toronto").body());
        List<String> ids = new ArrayList<>();
        body.at("/error/details/candidates").forEach(c -> ids.add(c.at("/id").asText()));
        assertThat(ids).contains("nhl:tor", "nba:tor", "mlb:tor");
    }

    @Test
    void mcpInputSchemaRejectsOutOfRangeAndUnknownArguments() throws Exception {
        JsonNode tooMany =
                mcp(
                        "tools/call",
                        Map.of(
                                "name",
                                "sports_get_games",
                                "arguments",
                                Map.of("league", "nhl", "limit", 5000)));
        assertThat(tooMany.at("/result/isError").asBoolean() || tooMany.has("error")).isTrue();
        JsonNode extra =
                mcp(
                        "tools/call",
                        Map.of(
                                "name",
                                "sports_get_games",
                                "arguments",
                                Map.of("league", "nhl", "url", "http://evil")));
        assertThat(extra.at("/result/isError").asBoolean() || extra.has("error")).isTrue();
    }

    @Test
    void unknownRestPathIsJson404() throws Exception {
        HttpResponse<String> r = get("/api/v1/nope");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(r.body()).at("/error/code").asText()).isEqualTo("not_found");
    }

    @Test
    void restIsCorsReadableAndCacheable() throws Exception {
        HttpResponse<String> r = get("/api/v1/leagues");
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        assertThat(r.headers().firstValue("Cache-Control")).contains("public, max-age=3600");
        assertThat(get("/api/v1/games/live").headers().firstValue("Cache-Control"))
                .contains("public, max-age=5");
    }

    // ---------------------------------------------------------------- OpenAPI

    @Test
    void openApiDocumentIsServedAndSelfConsistent() throws Exception {
        JsonNode doc = JSON.readTree(get("/openapi.json").body());
        assertThat(doc.at("/openapi").asText()).isEqualTo("3.1.0");
        assertThat(doc.at("/paths").size()).isEqualTo(7);
        List<String> refs = new ArrayList<>();
        collectRefs(doc, refs);
        assertThat(refs).isNotEmpty();
        for (String ref : refs) {
            assertThat(doc.at(ref.substring(1)).isMissingNode()).as("dangling " + ref).isFalse();
        }
    }

    private static void collectRefs(JsonNode n, List<String> out) {
        if (n.isObject()) {
            n.fields()
                    .forEachRemaining(
                            e -> {
                                if (e.getKey().equals("$ref")) {
                                    out.add(e.getValue().asText());
                                } else {
                                    collectRefs(e.getValue(), out);
                                }
                            });
        } else if (n.isArray()) {
            n.forEach(x -> collectRefs(x, out));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void assertConforms(String shape, JsonNode body) {
        assertConformsTo(Schemas.bundled().envelope(shape), body);
    }

    private static void assertConformsTo(Map<String, Object> schema, JsonNode body) {
        var result =
                new DefaultJsonSchemaValidator(JSON)
                        .validate(schema, JSON.convertValue(body, Object.class));
        assertThat(result.valid()).as(result.errorMessage() + " in " + body).isTrue();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode mcp(String method, Object params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", method);
        body.put("params", params);
        HttpResponse<String> res =
                HTTP.send(
                        HttpRequest.newBuilder(URI.create(base + "/mcp"))
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json, text/event-stream")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }
}
