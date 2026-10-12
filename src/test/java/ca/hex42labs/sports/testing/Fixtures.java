package ca.hex42labs.sports.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Loads recorded upstream payloads from {@code src/test/resources/fixtures/<path>.json.gz}. */
public final class Fixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {}

    /** Raw JSON text, e.g. {@code Fixtures.text("espn/nfl/teams")}. */
    public static String text(String path) {
        try (InputStream in = open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String path) {
        try (InputStream in = open(path)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String path) throws IOException {
        String resource = "/fixtures/" + path + ".json.gz";
        InputStream raw = Fixtures.class.getResourceAsStream(resource);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "No fixture " + resource + " (see scripts/fixtures.manifest)");
        }
        return new GZIPInputStream(raw);
    }
}
