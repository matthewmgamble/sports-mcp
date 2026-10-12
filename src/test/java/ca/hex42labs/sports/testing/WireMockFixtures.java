package ca.hex42labs.sports.testing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Serve recorded fixtures from WireMock exactly as upstream does (gzip-encoded). */
public final class WireMockFixtures {
    private WireMockFixtures() {}

    public static ResponseDefinitionBuilder gz(String fixture) {
        try (InputStream in =
                WireMockFixtures.class.getResourceAsStream("/fixtures/" + fixture + ".json.gz")) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + fixture);
            }
            return aResponse()
                    .withHeader("Content-Encoding", "gzip")
                    .withHeader("Content-Type", "application/json")
                    .withBody(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
