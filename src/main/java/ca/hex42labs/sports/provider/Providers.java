package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.League;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Routes each league to the provider that serves it. */
public final class Providers {

    private final Map<String, SportsProvider> byId = new LinkedHashMap<>();

    public Providers(List<SportsProvider> providers) {
        for (SportsProvider p : providers) {
            if (byId.put(p.id(), p) != null) {
                throw new IllegalArgumentException("Duplicate provider " + p.id());
            }
        }
    }

    public SportsProvider forLeague(League league) {
        SportsProvider p = byId.get(league.provider());
        if (p == null) {
            throw new IllegalStateException(
                    "No provider '" + league.provider() + "' for league " + league.id());
        }
        return p;
    }
}
