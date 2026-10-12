package ca.hex42labs.sports.provider.espn;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Wire-level behaviour: the exact URLs we build and gzip handling with real fixture bytes. */
@WireMockTest
class EspnProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");

    private static EspnProvider provider(WireMockRuntimeInfo wm) {
        return new EspnProvider(
                UpstreamHttp.withDefaults(new ObjectMapper(), "test"),
                wm.getHttpBaseUrl() + "/",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scoreboardRequestsOneEasternDay(WireMockRuntimeInfo wm) throws Exception {
        stubGz(
                wm,
                "/football/nfl/scoreboard?dates=20260913",
                "espn/nfl/scoreboard-20260913-final-ot");
        ProviderBatch batch =
                provider(wm).fetchScoreboard(TestLeagues.get("nfl"), LocalDate.of(2026, 9, 13));
        assertThat(batch.games()).hasSize(13);
        assertThat(batch.teams()).hasSize(26);
        assertThat(batch.fetchedAt()).isEqualTo(NOW);
        assertThat(batch.bucket()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    void collegeBasketballAsksForAllOfDivisionOne(WireMockRuntimeInfo wm) throws Exception {
        stubGz(
                wm,
                "/basketball/mens-college-basketball/scoreboard?dates=20260214&groups=50&limit=400",
                "espn/ncaab/scoreboard-20260214-d1");
        assertThat(
                        provider(wm)
                                .fetchScoreboard(
                                        TestLeagues.get("ncaab"), LocalDate.of(2026, 2, 14))
                                .games())
                .hasSize(132);
    }

    @Test
    void teamsUseLeagueQuery(WireMockRuntimeInfo wm) throws Exception {
        stubGz(wm, "/football/college-football/teams?limit=1000", "espn/ncaaf/teams");
        assertThat(provider(wm).fetchTeams(TestLeagues.get("ncaaf"))).hasSize(762);
    }

    @Test
    void upstreamErrorsPropagate(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        get(urlEqualTo("/football/nfl/scoreboard?dates=20261012"))
                                .willReturn(aResponse().withStatus(502)));
        assertThatThrownBy(
                        () ->
                                provider(wm)
                                        .fetchScoreboard(
                                                TestLeagues.get("nfl"), LocalDate.of(2026, 10, 12)))
                .isInstanceOf(ProviderException.class);
    }

    @Test
    void scheduleIsOneRequestPerDayAndSurvivesABadDay(WireMockRuntimeInfo wm) throws Exception {
        stubGz(
                wm,
                "/football/nfl/scoreboard?dates=20260913",
                "espn/nfl/scoreboard-20260913-final-ot");
        wm.getWireMock()
                .register(
                        get(urlEqualTo("/football/nfl/scoreboard?dates=20260914"))
                                .willReturn(aResponse().withStatus(502)));
        stubGz(
                wm,
                "/football/nfl/scoreboard?dates=20260915",
                "espn/nfl/scoreboard-20260715-offseason");
        ScheduleResult r =
                provider(wm)
                        .fetchSchedule(
                                TestLeagues.get("nfl"),
                                LocalDate.of(2026, 9, 13),
                                LocalDate.of(2026, 9, 15));
        assertThat(r.games()).hasSize(13);
        assertThat(r.failures())
                .singleElement()
                .asString()
                .startsWith("2026-09-14")
                .contains("502");
        assertThat(r.fetchedAt()).isEqualTo(NOW);
    }

    @Test
    void scheduleThrowsWhenEveryDayFails(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                                        "/football/nfl/scoreboard"))
                                .willReturn(aResponse().withStatus(503)));
        assertThatThrownBy(
                        () ->
                                provider(wm)
                                        .fetchSchedule(
                                                TestLeagues.get("nfl"),
                                                LocalDate.of(2026, 9, 13),
                                                LocalDate.of(2026, 9, 14)))
                .isInstanceOf(ProviderException.class);
    }

    /** Serve a fixture exactly as ESPN does: gzip-encoded. */
    private static void stubGz(WireMockRuntimeInfo wm, String url, String fixture)
            throws Exception {
        byte[] gz;
        try (InputStream in =
                EspnProviderTest.class.getResourceAsStream("/fixtures/" + fixture + ".json.gz")) {
            gz = in.readAllBytes();
        }
        wm.getWireMock()
                .register(
                        get(urlEqualTo(url))
                                .willReturn(
                                        aResponse()
                                                .withHeader("Content-Encoding", "gzip")
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(gz)));
    }
}
