package ca.hex42labs.sports.api.http;

/** Classic token bucket. Thread-safe; time from {@link System#nanoTime()}. */
final class TokenBucket {

    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long last;

    TokenBucket(int perMinute, int burst) {
        this.capacity = Math.max(1, burst);
        this.tokensPerNano = perMinute / 60e9;
        this.tokens = capacity;
        this.last = System.nanoTime();
    }

    /**
     * @return 0 if a token was taken, else nanoseconds until one will be available
     */
    synchronized long tryTake() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - last) * tokensPerNano);
        last = now;
        if (tokens >= 1) {
            tokens -= 1;
            return 0;
        }
        return tokensPerNano <= 0 ? Long.MAX_VALUE : (long) Math.ceil((1 - tokens) / tokensPerNano);
    }
}
