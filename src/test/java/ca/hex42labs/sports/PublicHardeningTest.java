package ca.hex42labs.sports;

import static ca.hex42labs.sports.testing.WireMockFixtures.gz;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.api.http.ClientIdentity;
import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.testing.FixtureUpstreams;
import ca.hex42labs.sports.testing.LiveFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 5 acceptance: a flood from one client is turned away without raising upstream traffic,
 * many concurrent clients share upstream fetches, and operators can see it all in /metrics. Uses
 * the real clock and a synthesised live NFL game so live refresh actually happens.
 */
@WireMockTest
class PublicHardeningTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NFL_SCOREBOARD = "/espn/football/nfl/scoreboard";

    @TempDir Path tmp;

    /** Upstream: one live NFL game, every other league quiet (off-season payloads). */
    private void stubQuietUpstreamsWithOneLiveGame(WireMockRuntimeInfo wm) {
        FixtureUpstreams.stub(wm);
        var mock = wm.getWireMock();
        mock.register(
                get(urlPathEqualTo(NFL_SCOREBOARD))
                        .atPriority(1)
                        .willReturn(
                                okJson(
                                        LiveFixtures.nflLive(
                                                Instant.now().minus(Duration.ofHours(1)),
                                                17,
                                                10))));
        for (String p :
                List.of(
                        "basketball/nba",
                        "football/college-football",
                        "basketball/mens-college-basketball",
                        "soccer/eng.1",
                        "soccer/esp.1",
                        "soccer/ger.1")) {
            mock.register(
                    get(urlPathEqualTo("/espn/" + p + "/scoreboard"))
                            .atPriority(1)
                            .willReturn(gz("espn/nba/scoreboard-20260801-offseason")));
        }
        mock.register(
                get(urlPathMatching("/nhl/score/.*"))
                        .atPriority(1)
                        .willReturn(gz("nhl/score-20260715-offseason")));
        mock.register(
                get(urlPathEqualTo("/mlb/schedule"))
                        .atPriority(2)
                        .willReturn(gz("mlb/schedule-20270115-offseason")));
    }

    private App app(WireMockRuntimeInfo wm, Map<String, String> overrides) {
        Map<String, String> env = FixtureUpstreams.env(wm, tmp.resolve("h.db"));
        env.putAll(overrides);
        App app = new App(AppConfig.fromMap(env), Clock.systemUTC()).start();
        // Seed: the live game is known before traffic arrives.
        app.scores().day(app.leagues().find("nfl").orElseThrow(), app.scores().today(), true);
        return app;
    }

    @Test
    void floodFromOneClientIsRefusedWithoutRaisingUpstreamTraffic(WireMockRuntimeInfo wm)
            throws Exception {
        stubQuietUpstreamsWithOneLiveGame(wm);
        try (App app =
                app(
                        wm,
                        Map.of(
                                "RATE_LIMIT_PER_MIN",
                                "60",
                                "RATE_LIMIT_BURST",
                                "10",
                                "RATE_LIMIT_REFRESH_PER_MIN",
                                "6",
                                "API_KEYS_SHA256",
                                ClientIdentity.sha256("trusted-partner")))) {
            String base = "http://127.0.0.1:" + app.port();
            int upstreamBefore = nflCalls(wm);

            AtomicInteger ok = new AtomicInteger();
            AtomicInteger limited = new AtomicInteger();
            String retryAfter = null;
            for (int i = 0; i < 300; i++) {
                HttpResponse<String> r = httpGet(base + "/api/v1/games/live?refresh=true", null);
                if (r.statusCode() == 200) {
                    ok.incrementAndGet();
                } else if (r.statusCode() == 429) {
                    limited.incrementAndGet();
                    retryAfter = r.headers().firstValue("Retry-After").orElse(null);
                    assertThat(JSON.readTree(r.body()).at("/error/code").asText())
                            .isEqualTo("rate_limited");
                }
            }
            // Burst of 10 plus a trickle of refill during the loop.
            assertThat(ok.get()).isBetween(10, 15);
            assertThat(limited.get()).isEqualTo(300 - ok.get());
            assertThat(retryAfter).isNotNull();

            // MCP tool calls share the same budget and get a readable tool error.
            JsonNode call = mcp(base, "sports_get_live_scores", Map.of("refresh", true), null);
            assertThat(call.at("/result/isError").asBoolean()).isTrue();
            assertThat(call.at("/result/content/0/text").asText()).startsWith("rate_limited:");
            // ...but discovery calls aren't counted.
            assertThat(mcpRaw(base, "tools/list", Map.of(), null).at("/result/tools").size())
                    .isEqualTo(6);

            // Spoofed forwarding headers from an untrusted peer don't buy a fresh bucket.
            HttpResponse<String> spoofed =
                    HTTP.send(
                            HttpRequest.newBuilder(URI.create(base + "/api/v1/leagues"))
                                    .header("X-Forwarded-For", "198.51.100.77")
                                    .header("CF-Connecting-IP", "198.51.100.78")
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(spoofed.statusCode()).isEqualTo(429);

            // A partner with an API key has its own, larger budget.
            assertThat(httpGet(base + "/api/v1/games/live", "trusted-partner").statusCode())
                    .isEqualTo(200);

            // The flood (300 refresh=true requests) cost upstream at most one more scoreboard call.
            assertThat(nflCalls(wm) - upstreamBefore).isLessThanOrEqualTo(1);

            String metrics = scrape(app);
            assertThat(metrics)
                    .contains("sports_rate_limited_total{interface=\"rest\",scope=\"general\"}");
            assertThat(metrics)
                    .contains("sports_rate_limited_total{interface=\"mcp\",scope=\"general\"}");
        }
    }

    @Test
    void manyConcurrentClientsShareUpstreamFetches(WireMockRuntimeInfo wm) throws Exception {
        stubQuietUpstreamsWithOneLiveGame(wm);
        try (App app = app(wm, Map.of())) { // FixtureUpstreams.env lifts rate limits
            String base = "http://127.0.0.1:" + app.port();
            int before = nflCalls(wm);
            long start = System.nanoTime();
            List<Future<Integer>> results = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int c = 0; c < 50; c++) {
                    int client = c;
                    results.add(
                            pool.submit(
                                    () -> {
                                        int good = 0;
                                        for (int i = 0; i < 40; i++) {
                                            if (client % 2 == 0) {
                                                good +=
                                                        httpGet(
                                                                                        base
                                                                                                + "/api/v1/games/live?league=nfl&refresh=true",
                                                                                        null)
                                                                                .statusCode()
                                                                        == 200
                                                                ? 1
                                                                : 0;
                                            } else {
                                                good +=
                                                        mcp(
                                                                                base,
                                                                                "sports_get_live_scores",
                                                                                Map.of(
                                                                                        "league",
                                                                                        "nfl"),
                                                                                null)
                                                                        .at("/result/isError")
                                                                        .asBoolean(false)
                                                                ? 0
                                                                : 1;
                                            }
                                        }
                                        return good;
                                    }));
                }
                int total = 0;
                for (var f : results) {
                    total += f.get();
                }
                assertThat(total).isEqualTo(2_000);
            }
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            // 2,000 requests (half forcing refresh): upstream sees about one call per 5-second
            // floor window for the single live league-day.
            long budget = took.toSeconds() / 5 + 2;
            assertThat(nflCalls(wm) - before).as("took %s", took).isLessThanOrEqualTo((int) budget);

            JsonNode live =
                    JSON.readTree(httpGet(base + "/api/v1/games/live?league=nfl", null).body());
            assertThat(live.at("/data/0/id").asText()).isEqualTo("nfl:" + LiveFixtures.eventId());
            assertThat(live.at("/data/0/home/score").asInt()).isEqualTo(17);
            assertThat(live.at("/meta/isStale").asBoolean()).isFalse();

            String metrics = scrape(app);
            // Requests inside the freshness window/floor never reach the coordinator's fetch path.
            assertThat(metrics)
                    .contains("sports_refresh_fetch_total{kind=\"bucket\",outcome=\"ok\"}");
            assertThat(metrics)
                    .contains(
                            "sports_upstream_request_seconds_count{outcome=\"ok\",provider=\"espn\"}");
            assertThat(metrics)
                    .contains(
                            "sports_http_request_seconds_count{interface=\"mcp\",status=\"200\"}");
            assertThat(metrics).contains("sports_circuit_state{provider=\"espn\"} 0.0");
            assertThat(metrics)
                    .contains("sports_sync_age_seconds{kind=\"SCHEDULE\",league=\"nfl\"}");
            assertThat(metrics).contains("jvm_memory_used_bytes");
        }
    }

    @Test
    void landingPageAndHealthAreNotRateLimited(WireMockRuntimeInfo wm) throws Exception {
        stubQuietUpstreamsWithOneLiveGame(wm);
        try (App app = app(wm, Map.of("RATE_LIMIT_PER_MIN", "1", "RATE_LIMIT_BURST", "1"))) {
            String base = "http://127.0.0.1:" + app.port();
            for (int i = 0; i < 5; i++) {
                assertThat(httpGet(base + "/health/ready", null).statusCode()).isEqualTo(200);
            }
            HttpResponse<String> landing = httpGet(base + "/", null);
            assertThat(landing.statusCode()).isEqualTo(200);
            assertThat(landing.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/html");
            assertThat(landing.body())
                    .contains("sports-mcp")
                    .contains("/mcp")
                    .contains("not affiliated");
            assertThat(landing.headers().firstValue("Server")).isEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int nflCalls(WireMockRuntimeInfo wm) {
        return wm.getWireMock().find(getRequestedFor(urlPathEqualTo(NFL_SCOREBOARD))).size();
    }

    private static String scrape(App app) throws Exception {
        return httpGet("http://127.0.0.1:" + app.managementPort() + "/metrics", null).body();
    }

    private static HttpResponse<String> httpGet(String url, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (apiKey != null) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode mcp(String base, String tool, Map<String, Object> args, String apiKey)
            throws Exception {
        return mcpRaw(base, "tools/call", Map.of("name", tool, "arguments", args), apiKey);
    }

    private static JsonNode mcpRaw(String base, String method, Object params, String apiKey)
            throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(base + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "jsonrpc", "2.0", "id", 7, "method", method,
                                                        "params", params))));
        if (apiKey != null) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }
}
