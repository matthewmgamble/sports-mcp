package ca.hex42labs.sports.domain;

import java.util.List;

/**
 * A team in one league.
 *
 * @param id canonical id, e.g. {@code nhl:tor} (see {@link Ids})
 * @param providerTeamId the provider's own id ({@code TOR} for NHL, {@code 141} for MLB, ...)
 */
public record Team(
        String id,
        String league,
        String provider,
        String providerTeamId,
        String name,
        String shortName,
        String abbreviation,
        String location,
        String logoUrl,
        List<String> aliases) {

    public Team {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public Team withAliases(List<String> value) {
        return new Team(
                id,
                league,
                provider,
                providerTeamId,
                name,
                shortName,
                abbreviation,
                location,
                logoUrl,
                value);
    }
}
