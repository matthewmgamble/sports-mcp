package ca.hex42labs.sports.application;

import java.time.Instant;
import java.util.Set;

/**
 * Data plus how current it is.
 *
 * @param asOf when the oldest piece of this data was fetched from upstream
 * @param stale some of it is older than its freshness policy allows (refresh failed or pending)
 * @param staleReason why, when stale ("espn: temporarily unavailable (circuit open)")
 * @param sources providers the data came from
 */
public record Refreshed<T>(
        T data, Instant asOf, boolean stale, String staleReason, Set<String> sources) {}
