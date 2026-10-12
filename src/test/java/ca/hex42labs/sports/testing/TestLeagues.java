package ca.hex42labs.sports.testing;

import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.domain.League;
import java.util.List;

/** The real league definitions, for tests. */
public final class TestLeagues {

    public static final LeagueRegistry REGISTRY = LeagueRegistry.standard(List.of());

    private TestLeagues() {}

    public static League get(String id) {
        return REGISTRY.find(id).orElseThrow();
    }
}
