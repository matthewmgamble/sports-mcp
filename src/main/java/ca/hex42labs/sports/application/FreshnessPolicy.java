package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.Game;
import java.time.Duration;
import java.time.Instant;

/**
 * How old a game's data may be before a request should refresh it. Live games are expensive to get
 * wrong and cheap to refresh (one scoreboard call covers a whole league-day); games far in the
 * future or long finished barely change, and background sync keeps them current.
 */
public final class FreshnessPolicy {

    /** Effectively "never refresh on request". */
    public static final Duration NEVER = Duration.ofDays(3650);

    /**
     * @param live in-progress games
     * @param pregame scheduled games starting within {@link #PREGAME_WINDOW}
     * @param overdue scheduled games past their start time (late start, delay, or a status lag)
     * @param recentFinal finals within {@link #RECENT_FINAL_WINDOW} of the start (late corrections)
     * @param emptyBucket a league-day with no games, before asking again
     */
    public record Settings(
            Duration live,
            Duration pregame,
            Duration overdue,
            Duration recentFinal,
            Duration emptyBucket) {

        public static Settings defaults() {
            return new Settings(
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(2));
        }
    }

    static final Duration PREGAME_WINDOW = Duration.ofHours(1);

    /** Games rarely run past 5 hours (extra-inning baseball); finals after that are settled. */
    static final Duration RECENT_FINAL_WINDOW = Duration.ofHours(6);

    /** Matches the near-term background sync interval. */
    static final Duration NEAR_TERM = Duration.ofMinutes(30);

    static final Duration NEAR_TERM_WINDOW = Duration.ofHours(48);

    /** Matches the full background sync interval. */
    static final Duration BACKGROUND = Duration.ofHours(6);

    private final Settings settings;

    public FreshnessPolicy(Settings settings) {
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    public Duration maxAge(Game game, Instant now) {
        Duration untilStart = Duration.between(now, game.startsAt());
        return switch (game.status()) {
            case LIVE -> settings.live();
            case SCHEDULED, UNKNOWN -> {
                if (untilStart.isNegative() || untilStart.isZero()) {
                    yield settings.overdue();
                }
                if (untilStart.compareTo(PREGAME_WINDOW) <= 0) {
                    yield settings.pregame();
                }
                yield untilStart.compareTo(NEAR_TERM_WINDOW) <= 0 ? NEAR_TERM : BACKGROUND;
            }
            case FINAL ->
                    untilStart.negated().compareTo(RECENT_FINAL_WINDOW) < 0
                            ? settings.recentFinal()
                            : NEVER;
            case SUSPENDED -> settings.recentFinal();
            case POSTPONED, CANCELLED -> BACKGROUND;
        };
    }

    /** Older than its max age (strictly: exactly at the limit is still fresh). */
    public boolean isStale(Game game, Instant now) {
        return Duration.between(game.fetchedAt(), now).compareTo(maxAge(game, now)) > 0;
    }
}
