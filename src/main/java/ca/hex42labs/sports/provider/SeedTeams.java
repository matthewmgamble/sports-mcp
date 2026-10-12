package ca.hex42labs.sports.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Bundled team lists ({@code src/main/resources/seed}) for leagues whose team endpoint can come
 * back empty (NHL standings in the off-season) or fail. Stored in the upstream's own shape, so the
 * provider's normal mapper reads them.
 */
public final class SeedTeams {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SeedTeams() {}

    public static JsonNode load(String resource) {
        try (InputStream in = SeedTeams.class.getResourceAsStream("/seed/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing seed resource " + resource);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
