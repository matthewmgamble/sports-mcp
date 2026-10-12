package ca.hex42labs.sports.provider;

/** An upstream call failed. {@link #kind()} drives retry, breaker and stale-fallback decisions. */
public class ProviderException extends Exception {

    public enum Kind {
        /** Non-2xx response; see {@link #status()}. */
        HTTP_STATUS,
        TIMEOUT,
        /** Connection refused, reset, DNS, TLS... */
        NETWORK,
        /** Body wasn't the JSON we expected. */
        PARSE,
        /** Our own per-provider rate limit refused the call (upstream not contacted). */
        RATE_LIMITED,
        /** Circuit breaker is open after repeated failures (upstream not contacted). */
        CIRCUIT_OPEN
    }

    private final String provider;
    private final Kind kind;
    private final int status;

    public ProviderException(
            String provider, Kind kind, int status, String message, Throwable cause) {
        super(provider + ": " + message, cause);
        this.provider = provider;
        this.kind = kind;
        this.status = status;
    }

    public ProviderException(String provider, Kind kind, String message, Throwable cause) {
        this(provider, kind, 0, message, cause);
    }

    public String provider() {
        return provider;
    }

    public Kind kind() {
        return kind;
    }

    /** HTTP status for {@link Kind#HTTP_STATUS}, otherwise 0. */
    public int status() {
        return status;
    }
}
