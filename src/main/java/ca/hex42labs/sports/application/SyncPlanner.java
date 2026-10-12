package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.SyncState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides which schedule sync a league needs right now: the full look-ahead window every 6 hours,
 * the next 48 hours every 30 minutes, nothing otherwise. A full sync also covers the near-term
 * window, so it resets that clock too.
 */
public final class SyncPlanner {

    public static final Duration FULL_EVERY = Duration.ofHours(6);
    public static final Duration NEAR_EVERY = Duration.ofMinutes(30);
    public static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    public enum Due {
        FULL,
        NEAR_TERM,
        NONE
    }

    private final ScheduleSync sync;
    private final Clock clock;

    public SyncPlanner(ScheduleSync sync, Clock clock) {
        this.sync = sync;
        this.clock = clock;
    }

    public Due due(League league) {
        Instant now = clock.instant();
        Optional<SyncState> full = sync.state(league, SyncState.Kind.SCHEDULE);
        if (SyncDue.isDue(full, now, FULL_EVERY, RETRY_AFTER)) {
            return Due.FULL;
        }
        Optional<SyncState> near = sync.state(league, SyncState.Kind.SCHEDULE_NEAR);
        Instant lastNear = latestSuccess(full, near);
        boolean nearStale = lastNear == null || !lastNear.isAfter(now.minus(NEAR_EVERY));
        // Back off after any recent schedule attempt: a full sync that just failed shouldn't
        // be chased by a near-term one hitting the same broken upstream.
        boolean backingOff = recentlyAttempted(full, now) || recentlyAttempted(near, now);
        return nearStale && !backingOff ? Due.NEAR_TERM : Due.NONE;
    }

    private static boolean recentlyAttempted(Optional<SyncState> state, Instant now) {
        return state.map(SyncState::lastAttemptAt)
                .map(a -> a.isAfter(now.minus(RETRY_AFTER)))
                .orElse(false);
    }

    private static Instant latestSuccess(Optional<SyncState> a, Optional<SyncState> b) {
        Instant x = a.map(SyncState::lastSuccessAt).orElse(null);
        Instant y = b.map(SyncState::lastSuccessAt).orElse(null);
        if (x == null) {
            return y;
        }
        return y == null || x.isAfter(y) ? x : y;
    }
}
