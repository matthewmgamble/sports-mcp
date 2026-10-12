package ca.hex42labs.sports;

import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.domain.SyncState;
import ca.hex42labs.sports.testing.FixtureUpstreams;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 2 acceptance: from a cold start with no client traffic, every enabled league's schedule is
 * fetched and persisted, and survives a restart. Upstreams are WireMock serving recorded fixtures.
 */
@WireMockTest
class ColdStartSyncTest {

    private static final Instant NOW = Instant.parse("2026-10-12T14:00:00Z");
    private static final String TOKEN = "test-admin-token";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tmp;

    private void stubUpstreams(WireMockRuntimeInfo wm) {
        FixtureUpstreams.stub(wm);
    }

    private App app(WireMockRuntimeInfo wm, boolean background) {
        Map<String, String> env = FixtureUpstreams.env(wm, tmp.resolve("cold.db"));
        env.put("BACKGROUND_SYNC", String.valueOf(background));
        env.put("ADMIN_TOKEN", TOKEN);
        return new App(AppConfig.fromMap(env), Clock.fixed(NOW, ZoneOffset.UTC)).start();
    }

    @Test
    void coldStartPopulatesEveryLeagueAndSurvivesRestart(WireMockRuntimeInfo wm) throws Exception {
        stubUpstreams(wm);
        List<String> leagues;
        Map<String, Integer> counts;
        try (App app = app(wm, true)) {
            leagues = app.leagues().enabledIds();
            await().atMost(Duration.ofSeconds(60))
                    .until(() -> app.games().countByLeague().keySet().containsAll(leagues));
            counts = app.games().countByLeague();
            assertThat(counts)
                    .containsEntry("nhl", 51)
                    .containsEntry("mlb", 18)
                    .containsEntry("nfl", 14);

            // Every league's full window synced successfully: yesterday through +14 days, Eastern.
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(
                            () -> {
                                JsonNode state =
                                        admin(app, "GET", "/admin/sync-state").path("syncState");
                                for (String league : leagues) {
                                    JsonNode s = find(state, league, SyncState.Kind.SCHEDULE);
                                    assertThat(s.path("lastSuccessAt").isNull())
                                            .as(league)
                                            .isFalse();
                                    assertThat(s.path("error").isNull()).as(league).isTrue();
                                }
                            });
            JsonNode nfl =
                    find(
                            admin(app, "GET", "/admin/sync-state").path("syncState"),
                            "nfl",
                            SyncState.Kind.SCHEDULE);
            assertThat(nfl.path("windowStart").asText()).isEqualTo("2026-10-11");
            assertThat(nfl.path("windowEnd").asText()).isEqualTo("2026-10-26");
            JsonNode breakers = admin(app, "GET", "/admin/sync-state").path("circuitBreakers");
            assertThat(breakers.path("espn").asText()).isEqualTo("CLOSED");
            assertThat(breakers.path("nhl").asText()).isEqualTo("CLOSED");
            assertThat(breakers.path("mlb").asText()).isEqualTo("CLOSED");
            // ESPN: one request per day in the 16-day window.
            wm.getWireMock()
                    .verifyThat(
                            16,
                            com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                                    urlPathEqualTo("/espn/football/nfl/scoreboard")));
        }

        // Restart with background sync off: everything is still there, no upstream needed.
        wm.getWireMock().resetMappings();
        try (App app = app(wm, false)) {
            assertThat(app.games().countByLeague()).isEqualTo(counts);
            assertThat(app.teams().find("nhl:tor")).isPresent();
        }
    }

    @Test
    void adminResyncRequiresTokenAndRunsSync(WireMockRuntimeInfo wm) throws Exception {
        stubUpstreams(wm);
        try (App app = app(wm, false)) {
            String base = "http://127.0.0.1:" + app.managementPort();
            HttpResponse<String> noToken =
                    HTTP.send(
                            HttpRequest.newBuilder(URI.create(base + "/admin/resync?league=nhl"))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(noToken.statusCode()).isEqualTo(401);

            JsonNode teams = admin(app, "POST", "/admin/resync?league=nhl&kind=teams");
            assertThat(teams.path("ok").asBoolean()).isTrue();
            assertThat(teams.path("teams").asInt()).isEqualTo(32);

            JsonNode outcome = admin(app, "POST", "/admin/resync?league=nhl");
            assertThat(outcome.path("games").asInt()).isEqualTo(51);
            assertThat(outcome.path("from").asText()).isEqualTo("2026-10-11");
            assertThat(app.games().countByLeague()).containsEntry("nhl", 51);

            JsonNode window =
                    admin(app, "POST", "/admin/resync?league=nhl&from=2026-10-13&to=2026-10-14");
            assertThat(window.path("games").asInt()).isEqualTo(18);

            assertThat(adminStatus(app, "POST", "/admin/resync?league=nlf")).isEqualTo(404);
            assertThat(adminStatus(app, "POST", "/admin/resync")).isEqualTo(400);
            assertThat(
                            adminStatus(
                                    app,
                                    "POST",
                                    "/admin/resync?league=nhl&from=2026-01-01&to=2026-12-31"))
                    .isEqualTo(400);
        }
    }

    private static JsonNode find(JsonNode states, String league, SyncState.Kind kind) {
        for (JsonNode s : states) {
            if (s.path("league").asText().equals(league)
                    && s.path("kind").asText().equals(kind.name())) {
                return s;
            }
        }
        throw new AssertionError("No sync state for " + league + " " + kind + " in " + states);
    }

    private static JsonNode admin(App app, String method, String path) throws Exception {
        HttpResponse<String> res = adminCall(app, method, path);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private static int adminStatus(App app, String method, String path) throws Exception {
        return adminCall(app, method, path).statusCode();
    }

    private static HttpResponse<String> adminCall(App app, String method, String path)
            throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + app.managementPort() + path))
                        .header("Authorization", "Bearer " + TOKEN)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
