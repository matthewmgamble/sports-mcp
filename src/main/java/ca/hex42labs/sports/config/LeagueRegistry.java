package ca.hex42labs.sports.config;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.TeamKeyStyle;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The allowlist of leagues. Nothing outside this registry is ever fetched. */
public final class LeagueRegistry {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    // spotless:off
    /** Every league we know how to serve, in display order. */
    static final List<League> KNOWN =
            List.of(
                    espn("nfl", "NFL", "football", "football/nfl", "", "", "nfl",
                            TeamKeyStyle.ABBREVIATION, 4, EASTERN, true),
                    new League("nhl", "NHL", "hockey", "nhl", null, "", "", "nhl",
                            TeamKeyStyle.ABBREVIATION, 3, EASTERN, true),
                    espn("nba", "NBA", "basketball", "basketball/nba", "", "", "nba",
                            TeamKeyStyle.ABBREVIATION, 4, EASTERN, true),
                    new League("mlb", "MLB", "baseball", "mlb", null, "", "", "mlb",
                            TeamKeyStyle.ABBREVIATION, 9, EASTERN, true),
                    // Default college football scoreboard is already FBS.
                    espn("ncaaf", "NCAA Football", "football", "football/college-football",
                            "limit=300", "limit=1000", "ncaa",
                            TeamKeyStyle.PROVIDER_ID, 4, EASTERN, true),
                    // Default college basketball scoreboard is a featured subset; groups=50 is D-I.
                    espn("ncaab", "NCAA Men's Basketball", "basketball",
                            "basketball/mens-college-basketball", "groups=50&limit=400",
                            "limit=1000", "ncaa", TeamKeyStyle.PROVIDER_ID, 2, EASTERN, true),
                    espn("epl", "Premier League", "soccer", "soccer/eng.1", "", "", "soccer",
                            TeamKeyStyle.PROVIDER_ID, 2, ZoneId.of("Europe/London"), true),
                    espn("laliga", "La Liga", "soccer", "soccer/esp.1", "", "", "soccer",
                            TeamKeyStyle.PROVIDER_ID, 2, ZoneId.of("Europe/Madrid"), true),
                    espn("bundesliga", "Bundesliga", "soccer", "soccer/ger.1", "", "", "soccer",
                            TeamKeyStyle.PROVIDER_ID, 2, ZoneId.of("Europe/Berlin"), true),
                    // Kept for the next tournament; national teams use the "countries" logos.
                    espn("fifa", "FIFA World Cup", "soccer", "soccer/fifa.world", "", "",
                            "countries", TeamKeyStyle.PROVIDER_ID, 2, EASTERN, false));
    // spotless:on

    private final Map<String, League> leagues;

    private LeagueRegistry(Map<String, League> leagues) {
        this.leagues = leagues;
    }

    /**
     * The standard registry. An empty {@code enabledOverride} keeps each league's default; a
     * non-empty one enables exactly those leagues (unknown ids fail fast at startup).
     */
    public static LeagueRegistry standard(Collection<String> enabledOverride) {
        Set<String> override = Set.copyOf(enabledOverride);
        for (String id : override) {
            if (KNOWN.stream().noneMatch(l -> l.id().equals(id))) {
                throw new IllegalArgumentException(
                        "Unknown league '" + id + "' in ENABLED_LEAGUES. Known: " + knownIds());
            }
        }
        Map<String, League> map = new LinkedHashMap<>();
        for (League l : KNOWN) {
            map.put(l.id(), override.isEmpty() ? l : l.withEnabled(override.contains(l.id())));
        }
        return new LeagueRegistry(map);
    }

    /** Enabled league by id. Disabled and unknown leagues are both "absent" to callers. */
    public Optional<League> find(String id) {
        League l = leagues.get(id);
        return l != null && l.enabled() ? Optional.of(l) : Optional.empty();
    }

    public List<League> enabled() {
        return leagues.values().stream().filter(League::enabled).toList();
    }

    public List<String> enabledIds() {
        return enabled().stream().map(League::id).toList();
    }

    private static String knownIds() {
        return String.join(", ", KNOWN.stream().map(League::id).toList());
    }

    private static League espn(
            String id,
            String name,
            String sport,
            String path,
            String scoreboardQuery,
            String teamsQuery,
            String logoSlug,
            TeamKeyStyle keyStyle,
            int periods,
            ZoneId zone,
            boolean enabled) {
        return new League(
                id,
                name,
                sport,
                "espn",
                path,
                scoreboardQuery,
                teamsQuery,
                logoSlug,
                keyStyle,
                periods,
                zone,
                enabled);
    }
}
