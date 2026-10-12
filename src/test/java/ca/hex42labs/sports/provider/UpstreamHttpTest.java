package ca.hex42labs.sports.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UpstreamHttpTest {

    // WireMock gzips responses itself when asked; off, so tests control the exact bytes on the
    // wire.
    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance()
                    .options(wireMockConfig().dynamicPort().gzipDisabled(true))
                    .configureStaticDsl(true)
                    .build();

    private final UpstreamHttp http =
            new UpstreamHttp(
                    new ObjectMapper(),
                    "sports-mcp-test",
                    Duration.ofSeconds(2),
                    Duration.ofMillis(500));

    private static URI uri(String path) {
        return URI.create(wireMock.baseUrl() + path);
    }

    @Test
    void sendsIdentityHeadersAndParsesJson() throws Exception {
        wireMock.stubFor(get("/plain").willReturn(okJson("{\"a\":1}")));
        assertThat(http.getJson("t", uri("/plain")).path("a").asInt()).isEqualTo(1);
        wireMock.verify(
                getRequestedFor(urlEqualTo("/plain"))
                        .withHeader("User-Agent", equalTo("sports-mcp-test"))
                        .withHeader("Accept-Encoding", equalTo("gzip")));
    }

    @Test
    void decodesGzipBodies() throws Exception {
        wireMock.stubFor(
                get("/gz")
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Encoding", "gzip")
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(gzip("{\"events\":[]}"))));
        assertThat(http.getJson("t", uri("/gz")).has("events")).isTrue();
    }

    @Test
    void decodesGzipEvenWithoutHeader() throws Exception {
        wireMock.stubFor(get("/sneaky").willReturn(aResponse().withBody(gzip("{\"x\":true}"))));
        assertThat(http.getJson("t", uri("/sneaky")).path("x").asBoolean()).isTrue();
    }

    @Test
    void followsRedirects() throws Exception {
        wireMock.stubFor(
                get("/now")
                        .willReturn(
                                aResponse().withStatus(307).withHeader("Location", "/20262027")));
        wireMock.stubFor(get("/20262027").willReturn(okJson("{\"season\":20262027}")));
        assertThat(http.getJson("t", uri("/now")).path("season").asInt()).isEqualTo(20262027);
    }

    @Test
    void non2xxIsHttpStatusError() {
        wireMock.stubFor(
                get(urlPathEqualTo("/bad"))
                        .willReturn(
                                aResponse()
                                        .withStatus(400)
                                        .withBody(
                                                "{\"code\":400,\"message\":\"Failed to get"
                                                        + " events endpoint.\"}")));
        assertThatThrownBy(() -> http.getJson("espn", uri("/bad?dates=1-2")))
                .isInstanceOfSatisfying(
                        ProviderException.class,
                        e -> {
                            assertThat(e.kind()).isEqualTo(ProviderException.Kind.HTTP_STATUS);
                            assertThat(e.status()).isEqualTo(400);
                            assertThat(e.provider()).isEqualTo("espn");
                            assertThat(e.getMessage())
                                    .contains("/bad?dates=1-2")
                                    .doesNotContain("localhost");
                        });
    }

    @Test
    void slowResponseIsTimeout() {
        wireMock.stubFor(get("/slow").willReturn(okJson("{}").withFixedDelay(2_000)));
        assertThatThrownBy(() -> http.getJson("t", uri("/slow")))
                .isInstanceOfSatisfying(
                        ProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.TIMEOUT));
    }

    @Test
    void malformedAndTruncatedBodiesAreParseErrors() throws Exception {
        wireMock.stubFor(get("/html").willReturn(aResponse().withBody("<html>oops</html>")));
        byte[] gz = gzip("{\"events\":[1,2,3]}");
        wireMock.stubFor(
                get("/truncated")
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Encoding", "gzip")
                                        .withBody(Arrays.copyOf(gz, gz.length / 2))));
        wireMock.stubFor(get("/half-json").willReturn(aResponse().withBody("{\"events\":[")));
        for (String path : new String[] {"/html", "/truncated", "/half-json"}) {
            assertThatThrownBy(() -> http.getJson("t", uri(path)))
                    .as(path)
                    .isInstanceOfSatisfying(
                            ProviderException.class,
                            e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.PARSE));
        }
    }

    @Test
    void connectionRefusedIsNetworkError() {
        assertThatThrownBy(() -> http.getJson("t", URI.create("http://127.0.0.1:1/nothing")))
                .isInstanceOfSatisfying(
                        ProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.NETWORK));
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
