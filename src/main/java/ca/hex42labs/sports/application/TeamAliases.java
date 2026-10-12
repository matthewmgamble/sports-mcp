package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.Team;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Curated nicknames ("leafs", "habs") from {@code team-aliases.json}, keyed by team id. */
public final class TeamAliases {

    private final Map<String, List<String>> byTeam;

    public TeamAliases(Map<String, List<String>> byTeam) {
        this.byTeam = Map.copyOf(byTeam);
    }

    public static TeamAliases bundled() {
        try (InputStream in = TeamAliases.class.getResourceAsStream("/team-aliases.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing team-aliases.json");
            }
            Map<String, Object> raw = new ObjectMapper().readValue(in, new TypeReference<>() {});
            Map<String, List<String>> map = new LinkedHashMap<>();
            raw.forEach(
                    (k, v) -> {
                        if (!k.startsWith("_") && v instanceof List<?> list) {
                            map.put(k, list.stream().map(String::valueOf).toList());
                        }
                    });
            return new TeamAliases(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The team with curated aliases merged into whatever it already had. */
    public Team apply(Team team) {
        List<String> extra = byTeam.get(team.id());
        if (extra == null) {
            return team;
        }
        Set<String> merged = new LinkedHashSet<>(team.aliases());
        merged.addAll(extra);
        return team.withAliases(List.copyOf(merged));
    }
}
