package ca.hex42labs.sports.api.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/**
 * Per-client request budgets. Buckets live in a bounded cache and are forgotten after 10 idle
 * minutes, so a flood of distinct IPs can't exhaust memory.
 */
public final class RateLimits {

    /**
     * @param perMinute sustained requests per minute (anonymous)
     * @param burst bucket size (anonymous)
     * @param refreshPerMinute {@code refresh=true} requests per minute (anonymous)
     * @param keyedMultiplier how many times higher limits are with an API key
     */
    public record Settings(int perMinute, int burst, int refreshPerMinute, int keyedMultiplier) {}

    public enum Scope {
        GENERAL,
        REFRESH
    }

    private final Settings settings;
    private final Cache<String, TokenBucket> buckets =
            Caffeine.newBuilder()
                    .expireAfterAccess(Duration.ofMinutes(10))
                    .maximumSize(200_000)
                    .build();

    public RateLimits(Settings settings) {
        this.settings = settings;
    }

    /**
     * @return 0 if allowed, else nanoseconds until the client may retry
     */
    public long tryAcquire(ClientIdentity.Client client, Scope scope) {
        int m = client.keyed() ? settings.keyedMultiplier() : 1;
        TokenBucket bucket =
                buckets.get(
                        scope + "|" + client.key(),
                        k ->
                                scope == Scope.GENERAL
                                        ? new TokenBucket(
                                                settings.perMinute() * m, settings.burst() * m)
                                        : new TokenBucket(
                                                settings.refreshPerMinute() * m,
                                                Math.max(1, settings.refreshPerMinute() / 2) * m));
        return bucket.tryTake();
    }
}
