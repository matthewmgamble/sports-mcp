package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A team's season so far: record and standing (which change after each game) and colour. Fetched on
 * demand and kept for {@link #TTL}, so upstream traffic doesn't grow with requests; when upstream
 * fails, the last known details are returned as stale.
 */
public final class TeamDetails {

    static final Duration TTL = Duration.ofMinutes(30);

    /**
     * @param color hex without '#'
     * @param record "3-2-1"; null when the team hasn't played in the current part of the season
     * @param standing "4th in Atlantic Division"
     */
    public record Details(String color, String record, String standing) {}

    /** Where details come from. */
    @FunctionalInterface
    public interface Source {
        Details fetch(League league, Team team) throws ProviderException;
    }

    /**
     * @param staleReason why {@code details} may be out of date (or missing), else null
     */
    public record Result(Details details, Instant asOf, String staleReason) {}

    private record Cached(Details details, Instant at) {}

    private final Source source;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public TeamDetails(Source source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public Result get(League league, Team team) {
        Instant now = clock.instant();
        Cached c = cache.get(team.id());
        if (c != null && c.at().plus(TTL).isAfter(now)) {
            return new Result(c.details(), c.at(), null);
        }
        try {
            Details d = source.fetch(league, team);
            cache.put(team.id(), new Cached(d, now));
            return new Result(d, now, null);
        } catch (ProviderException e) {
            return c == null
                    ? new Result(null, now, "team details unavailable: " + e.getMessage())
                    : new Result(c.details(), c.at(), "upstream unavailable: " + e.getMessage());
        }
    }
}
