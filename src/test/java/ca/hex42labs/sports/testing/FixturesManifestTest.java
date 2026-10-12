package ca.hex42labs.sports.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every manifest entry must have a recorded, parseable fixture checked in. */
class FixturesManifestTest {

    static Stream<String> manifestPaths() throws Exception {
        return Files.readAllLines(Path.of("scripts/fixtures.manifest")).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> l.split("\\s+")[0]);
    }

    @ParameterizedTest
    @MethodSource("manifestPaths")
    void fixtureIsRecordedAndValidJson(String path) {
        assertThat(Fixtures.json(path).isObject()).isTrue();
    }
}
