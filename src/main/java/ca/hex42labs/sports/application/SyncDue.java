package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.SyncState;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** When a background sync should run, derived from persisted state so restarts catch up. */
final class SyncDue {

    private SyncDue() {}

    /**
     * Due when the last success is older than {@code interval} (or never happened), unless an
     * attempt was made within {@code retryAfter}, which spaces out retries while upstream is down.
     */
    static boolean isDue(
            Optional<SyncState> state, Instant now, Duration interval, Duration retryAfter) {
        if (state.isEmpty()) {
            return true;
        }
        Instant success = state.get().lastSuccessAt();
        Instant attempt = state.get().lastAttemptAt();
        boolean stale = success == null || !success.isAfter(now.minus(interval));
        boolean backingOff = attempt != null && attempt.isAfter(now.minus(retryAfter));
        return stale && !backingOff;
    }
}
