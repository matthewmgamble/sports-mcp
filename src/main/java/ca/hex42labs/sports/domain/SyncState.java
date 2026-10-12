package ca.hex42labs.sports.domain;

import java.time.Instant;
import java.time.LocalDate;

/** Bookkeeping for a background sync of one kind of data for one league. */
public record SyncState(
        String league,
        Kind kind,
        LocalDate windowStart,
        LocalDate windowEnd,
        Instant lastSuccessAt,
        Instant lastAttemptAt,
        String error) {

    public enum Kind {
        TEAMS,
        /** Full look-ahead window (D-1 .. D+horizon), every 6 hours. */
        SCHEDULE,
        /** Next 48 hours, every 30 minutes. */
        SCHEDULE_NEAR
    }
}
