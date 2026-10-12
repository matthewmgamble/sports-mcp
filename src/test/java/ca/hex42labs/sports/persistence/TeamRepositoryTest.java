package ca.hex42labs.sports.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Team;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TeamRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-12T18:00:00Z");

    @TempDir Path tmp;

    private static Team tor(String name) {
        return new Team(
                "nhl:tor",
                "nhl",
                "nhl",
                "TOR",
                name,
                "Maple Leafs",
                "TOR",
                "Toronto",
                "https://a.espncdn.com/i/teamlogos/nhl/500/tor.png",
                List.of("leafs"));
    }

    @Test
    void upsertOverwritesButInsertMissingDoesNot() {
        try (Database db = Database.open(tmp.resolve("t.db").toString())) {
            var repo = new TeamRepository(db, new ObjectMapper());
            repo.upsertAll(List.of(tor("Toronto Maple Leafs")), NOW);
            repo.insertMissing(List.of(tor("Maple Leafs")), NOW);
            assertThat(repo.findById("nhl:tor").orElseThrow().name())
                    .isEqualTo("Toronto Maple Leafs");
            repo.upsertAll(List.of(tor("Toronto Maple Leafs Hockey Club")), NOW);
            assertThat(repo.findById("nhl:tor").orElseThrow().name())
                    .isEqualTo("Toronto Maple Leafs Hockey Club");
            assertThat(repo.findAll()).hasSize(1);
            assertThat(repo.findById("nhl:tor").orElseThrow().aliases()).containsExactly("leafs");
            assertThat(repo.findByLeague("nhl")).hasSize(1);
            assertThat(repo.findByLeague("nba")).isEmpty();
        }
    }
}
