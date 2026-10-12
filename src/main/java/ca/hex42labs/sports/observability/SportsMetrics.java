package ca.hex42labs.sports.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Named metrics, recorded on Micrometer's global registry so any class can count without wiring.
 * With no registry attached (unit tests) they're no-ops; the app attaches Prometheus.
 *
 * <p>Names follow Prometheus conventions; see docs/RUNBOOK.md for what each one means.
 */
public final class SportsMetrics {

    private SportsMetrics() {}

    /** One upstream HTTP call. outcome: ok, http_4xx, http_5xx, timeout, network, parse. */
    public static void upstream(String provider, String outcome, Duration took) {
        Timer.builder("sports_upstream_request")
                .description("Upstream HTTP calls")
                .tag("provider", provider)
                .tag("outcome", outcome)
                .register(Metrics.globalRegistry)
                .record(took);
    }

    /** A request asked for a refresh key; {@code coalesced} when it joined an existing fetch. */
    public static void refreshDemand(String kind, boolean coalesced) {
        Metrics.counter(
                        "sports_refresh_demand_total",
                        "kind",
                        kind,
                        "coalesced",
                        String.valueOf(coalesced))
                .increment();
    }

    /** A refresh fetch actually ran. kind: bucket, game, team; outcome: ok, failed. */
    public static void refreshFetch(String kind, String outcome) {
        Metrics.counter("sports_refresh_fetch_total", "kind", kind, "outcome", outcome).increment();
    }

    /**
     * The primary provider failed and a fallback was tried. operation: scoreboard, schedule_day,
     * team; outcome: ok, failed.
     */
    public static void fallback(String league, String operation, String outcome) {
        Metrics.counter(
                        "sports_fallback_total",
                        "league",
                        league,
                        "operation",
                        operation,
                        "outcome",
                        outcome)
                .increment();
    }

    public static void staleResponse(String operation) {
        Metrics.counter("sports_stale_responses_total", "operation", operation).increment();
    }

    /** scope: general, refresh; iface: rest, mcp. */
    public static void rateLimited(String scope, String iface) {
        Metrics.counter("sports_rate_limited_total", "scope", scope, "interface", iface)
                .increment();
    }

    public static void overloaded() {
        Metrics.counter("sports_overloaded_total").increment();
    }

    public static void unmappedStatus(String provider, String raw) {
        Metrics.counter(
                        "sports_status_unmapped_total",
                        "provider",
                        provider,
                        "raw",
                        raw == null ? "null" : raw)
                .increment();
    }

    /** One public request. iface: rest, mcp, other. */
    public static void httpRequest(String iface, int status, Duration took) {
        Timer.builder("sports_http_request")
                .tag("interface", iface)
                .tag("status", String.valueOf(status))
                .register(Metrics.globalRegistry)
                .record(took);
    }
}
