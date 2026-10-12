package ca.hex42labs.sports.domain;

import java.time.ZoneId;

/**
 * A competition we serve.
 *
 * @param id canonical key, e.g. {@code nfl}
 * @param provider which provider serves it: {@code espn}, {@code nhl} or {@code mlb}
 * @param providerPath provider-specific path, e.g. ESPN's {@code football/nfl} (null if unused)
 * @param scoreboardQuery extra query string for day scoreboards, e.g. {@code groups=50&limit=400}
 * @param teamsQuery extra query string for the team list, e.g. {@code limit=1000}
 * @param logoSlug ESPN team-logo CDN folder: {@code nfl}, {@code ncaa}, {@code soccer}, ...
 * @param regulationPeriods periods/halves/innings before overtime
 * @param displayTimezone the league's "home" timezone (late-game cutoff, default display)
 */
public record League(
        String id,
        String displayName,
        String sport,
        String provider,
        String providerPath,
        String scoreboardQuery,
        String teamsQuery,
        String logoSlug,
        TeamKeyStyle teamKeyStyle,
        int regulationPeriods,
        ZoneId displayTimezone,
        boolean enabled) {

    public boolean isSoccer() {
        return "soccer".equals(sport);
    }

    public League withEnabled(boolean value) {
        return new League(
                id,
                displayName,
                sport,
                provider,
                providerPath,
                scoreboardQuery,
                teamsQuery,
                logoSlug,
                teamKeyStyle,
                regulationPeriods,
                displayTimezone,
                value);
    }
}
