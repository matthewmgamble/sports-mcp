package ca.hex42labs.sports;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.testing.FixtureUpstreams;
import ca.hex42labs.sports.testing.LiveFixtures;
import ca.hex42labs.sports.testing.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The dev plan's failure scenarios: whatever upstream does, clients get the last good data, clearly
 * marked stale with a reason, and nothing crashes.
 */
@WireMockTest
class FaultToleranceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T22:00:00Z");
    private static final String SCOREBOARD = "/espn/football/nfl/scoreboard";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;
    private final MutableClock clock = new MutableClock(NOW);

    private App start(WireMockRuntimeInfo wm) throws Exception {
        FixtureUpstreams.stub(wm);
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo(SCOREBOARD))
                                .atPriority(5)
                                .willReturn(
                                        okJson(
                                                LiveFixtures.nflLive(
                                                        NOW.minus(Duration.ofHours(1)), 14, 10))));
        Map<String, String> env = FixtureUpstreams.env(wm, tmp.resolve("f.db"));
        env.put("UPSTREAM_TIMEOUT_MS", "400");
        App app = new App(AppConfig.fromMap(env), clock).start();
        // Seed the live game, as the background sync would have.
        app.scores().day(app.leagues().find("nfl").orElseThrow(), app.scores().today(), true);
        JsonNode seeded = game(app);
        assertThat(seeded.at("/data/status").asText()).isEqualTo("LIVE");
        assertThat(seeded.at("/meta/isStale").asBoolean()).isFalse();
        return app;
    }

    static Stream<Arguments> faults() {
        return Stream.of(
                Arguments.of("outage (503)", aResponse().withStatus(503), "HTTP 503"),
                Arguments.of("upstream rate limit (429)", aResponse().withStatus(429), "HTTP 429"),
                Arguments.of(
                        "malformed JSON",
                        aResponse().withBody("{\"events\": [ {\"id\": "),
                        "invalid JSON"),
                Arguments.of(
                        "HTML error page",
                        aResponse().withBody("<html>Service Unavailable</html>"),
                        "invalid JSON"),
                Arguments.of(
                        "connection reset",
                        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER),
                        "espn"),
                Arguments.of(
                        "garbage bytes",
                        aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE),
                        "espn"),
                Arguments.of(
                        "timeout", okJson("{\"events\":[]}").withFixedDelay(2_000), "timeout"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    void upstreamFaultServesLastGoodDataMarkedStale(
            String name, ResponseDefinitionBuilder fault, String reason, WireMockRuntimeInfo wm)
            throws Exception {
        try (App app = start(wm)) {
            var faultStub =
                    wm.getWireMock()
                            .register(
                                    get(urlPathEqualTo(SCOREBOARD))
                                            .atPriority(1)
                                            .willReturn(fault));
            clock.advance(Duration.ofSeconds(20)); // past the 15s live window

            JsonNode r = game(app);
            assertThat(r.at("/data/home/score").asInt() + r.at("/data/away/score").asInt())
                    .isEqualTo(24);
            assertThat(r.at("/data/status").asText()).isEqualTo("LIVE");
            assertThat(r.at("/meta/isStale").asBoolean()).isTrue();
            assertThat(r.at("/meta/staleReason").asText()).contains(reason);
            assertThat(r.at("/meta/asOf").asText()).isEqualTo("2026-10-12T22:00:00Z");

            // Upstream recovers: next request after the floor is fresh again.
            wm.getWireMock().removeStubMapping(faultStub);
            wm.getWireMock()
                    .register(
                            get(urlPathEqualTo(SCOREBOARD))
                                    .atPriority(1)
                                    .willReturn(
                                            okJson(
                                                    LiveFixtures.nflLive(
                                                            NOW.minus(Duration.ofHours(1)),
                                                            21,
                                                            10))));
            clock.advance(Duration.ofSeconds(6));
            JsonNode recovered = game(app);
            assertThat(recovered.at("/meta/isStale").asBoolean()).isFalse();
            assertThat(
                            recovered.at("/data/home/score").asInt()
                                    + recovered.at("/data/away/score").asInt())
                    .isEqualTo(31);
        }
    }

    @Test
    void sustainedOutageOpensTheCircuitAndStopsCallingUpstream(WireMockRuntimeInfo wm)
            throws Exception {
        try (App app = start(wm)) {
            wm.getWireMock()
                    .register(
                            get(urlPathEqualTo(SCOREBOARD))
                                    .atPriority(1)
                                    .willReturn(aResponse().withStatus(500)));
            for (int i = 0; i < 12; i++) {
                clock.advance(
                        Duration.ofSeconds(6)); // beyond the floor, so each request tries upstream
                game(app);
            }
            JsonNode r = game(app);
            assertThat(r.at("/meta/isStale").asBoolean()).isTrue();
            clock.advance(Duration.ofSeconds(6));
            int calls = upstreamCalls(wm);
            JsonNode open = game(app);
            assertThat(open.at("/meta/staleReason").asText()).contains("circuit open");
            assertThat(upstreamCalls(wm)).isEqualTo(calls);
            assertThat(open.at("/data/home/score").isInt()).isTrue();

            String metrics =
                    HTTP.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + app.managementPort()
                                                                    + "/metrics"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body();
            assertThat(metrics).contains("sports_circuit_state{provider=\"espn\"} 2.0");
            assertThat(metrics)
                    .contains(
                            "sports_upstream_request_seconds_count{outcome=\"http_5xx\",provider=\"espn\"}");
            assertThat(metrics).contains("sports_stale_responses_total{operation=\"game\"}");
        }
    }

    @Test
    void changedPayloadShapeKeepsLastGoodDataAndFlagsIt(WireMockRuntimeInfo wm) throws Exception {
        try (App app = start(wm)) {
            ObjectNode changed =
                    (ObjectNode)
                            JSON.readTree(
                                    LiveFixtures.nflLive(NOW.minus(Duration.ofHours(1)), 99, 99));
            ((ObjectNode) changed.path("events").path(0).path("competitions").path(0))
                    .remove("competitors");
            wm.getWireMock()
                    .register(
                            get(urlPathEqualTo(SCOREBOARD))
                                    .atPriority(1)
                                    .willReturn(okJson(changed.toString())));
            clock.advance(Duration.ofSeconds(20));
            JsonNode r = game(app);
            // The unparseable event is skipped, not saved with junk; the old state stays and is
            // flagged.
            assertThat(r.at("/data/home/score").asInt()).isNotEqualTo(99);
            assertThat(r.at("/meta/isStale").asBoolean()).isTrue();
        }
    }

    @Test
    void unknownStatusNameStillMapsAndIsCounted(WireMockRuntimeInfo wm) throws Exception {
        try (App app = start(wm)) {
            ObjectNode changed =
                    (ObjectNode)
                            JSON.readTree(
                                    LiveFixtures.nflLive(NOW.minus(Duration.ofHours(1)), 17, 10));
            ((ObjectNode) changed.path("events").path(0).path("status").path("type"))
                    .put("name", "STATUS_WEATHER_HOLD");
            wm.getWireMock()
                    .register(
                            get(urlPathEqualTo(SCOREBOARD))
                                    .atPriority(1)
                                    .willReturn(okJson(changed.toString())));
            clock.advance(Duration.ofSeconds(20));
            JsonNode r = game(app);
            assertThat(r.at("/data/status").asText()).isEqualTo("LIVE"); // state "in" fallback
            assertThat(r.at("/meta/isStale").asBoolean()).isFalse();
            String metrics =
                    HTTP.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + app.managementPort()
                                                                    + "/metrics"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body();
            assertThat(metrics)
                    .contains(
                            "sports_status_unmapped_total{provider=\"espn\",raw=\"STATUS_WEATHER_HOLD\"}");
        }
    }

    private static int upstreamCalls(WireMockRuntimeInfo wm) {
        return wm.getWireMock()
                .find(
                        com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                                urlPathEqualTo(SCOREBOARD)))
                .size();
    }

    private static JsonNode game(App app) throws Exception {
        HttpResponse<String> r =
                HTTP.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + app.port()
                                                        + "/api/v1/games/nfl:"
                                                        + LiveFixtures.eventId()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }
}
