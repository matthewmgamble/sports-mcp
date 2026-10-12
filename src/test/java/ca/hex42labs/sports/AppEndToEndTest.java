package ca.hex42labs.sports;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Boots the real app on a random port and talks MCP + HTTP to it like a client would. */
class AppEndToEndTest {

    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir static Path tmp;
    private static App app;
    private static String base;

    @BeforeAll
    static void start() {
        AppConfig config =
                AppConfig.fromMap(
                        Map.of(
                                "PORT", "0",
                                "DB_PATH", tmp.resolve("test.db").toString(),
                                "MCP_ALLOWED_ORIGINS", "https://allowed.example",
                                "BACKGROUND_SYNC", "false",
                                "MGMT_PORT", "0"));
        app = new App(config, Clock.fixed(NOW, ZoneOffset.UTC)).start();
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterAll
    static void stop() {
        app.close();
    }

    @Test
    void startupIsAllOrNothing() throws Exception {
        try (java.net.ServerSocket taken =
                new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            AppConfig cfg =
                    AppConfig.fromMap(
                            Map.of(
                                    "PORT",
                                    "0",
                                    "MGMT_PORT",
                                    String.valueOf(taken.getLocalPort()),
                                    "DB_PATH",
                                    tmp.resolve("bind.db").toString(),
                                    "BACKGROUND_SYNC",
                                    "false"));
            App doomed = new App(cfg, Clock.fixed(NOW, ZoneOffset.UTC));
            org.assertj.core.api.Assertions.assertThatThrownBy(doomed::start)
                    .hasMessageContaining("Port already in use");
            // The public listener was never left running.
            org.assertj.core.api.Assertions.assertThatThrownBy(doomed::port)
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void healthEndpointsReportUp() throws Exception {
        assertThat(get("/health/live").statusCode()).isEqualTo(200);
        HttpResponse<String> ready = get("/health/ready");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ready.body()).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void mcpInitializeListAndCallATool() throws Exception {
        JsonNode init =
                rpc(
                        "initialize",
                        Map.of(
                                "protocolVersion", "2025-06-18",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "test", "version", "1")));
        assertThat(init.at("/result/serverInfo/name").asText()).isEqualTo("sports-mcp");
        assertThat(init.at("/result/capabilities/tools").isObject()).isTrue();

        JsonNode list = rpc("tools/list", Map.of());
        assertThat(list.at("/result/tools").size()).isEqualTo(6);

        JsonNode call =
                rpc("tools/call", Map.of("name", "sports_list_leagues", "arguments", Map.of()));
        assertThat(call.at("/result/isError").asBoolean(false)).isFalse();
        assertThat(call.at("/result/structuredContent/data").size()).isEqualTo(9);
        assertThat(call.at("/result/structuredContent/meta/asOf").asText())
                .isEqualTo("2026-10-12T18:00:00Z");
        assertThat(call.at("/result/content/0/text").asText()).startsWith("Leagues: NFL (nfl)");
    }

    @Test
    void mcpRejectsUnknownBrowserOrigin() throws Exception {
        assertThat(mcpPost(rpcBody("tools/list", Map.of()), "https://evil.example").statusCode())
                .isEqualTo(403);
        assertThat(mcpPost(rpcBody("tools/list", Map.of()), "https://allowed.example").statusCode())
                .isEqualTo(200);
    }

    @Test
    void mcpGetIsNotAllowed() throws Exception {
        // Stateless server: no SSE stream to open.
        assertThat(get("/mcp").statusCode()).isEqualTo(405);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode rpc(String method, Object params) throws Exception {
        HttpResponse<String> res = mcpPost(rpcBody(method, params), null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private static String rpcBody(String method, Object params) throws Exception {
        return JSON.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    }

    private static HttpResponse<String> mcpPost(String body, String origin) throws Exception {
        HttpRequest.Builder req =
                HttpRequest.newBuilder(URI.create(base + "/mcp"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            req.header("Origin", origin);
        }
        return HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
