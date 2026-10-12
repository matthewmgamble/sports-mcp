package ca.hex42labs.sports.config;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runtime configuration, 12-factor style: everything comes from environment variables with defaults
 * that are safe for local development. See {@code .env.example} for the full list.
 */
public record AppConfig(
        String bindHost,
        int port,
        String mgmtBindHost,
        int mgmtPort,
        String dbPath,
        ZoneId defaultTimezone,
        List<String> mcpAllowedOrigins,
        List<String> enabledLeagues,
        String espnBaseUrl,
        String nhlBaseUrl,
        String mlbBaseUrl,
        boolean backgroundSync,
        int syncHorizonDays,
        int retentionFinishedDays,
        String adminToken,
        int freshLiveSeconds,
        int freshPregameSeconds,
        int freshRecentFinalSeconds,
        int freshEmptySeconds,
        int refreshFloorSeconds,
        int rateLimitPerMinute,
        int rateLimitBurst,
        int rateLimitRefreshPerMinute,
        int rateLimitKeyedMultiplier,
        List<String> apiKeysSha256,
        List<String> trustedProxyCidrs,
        int maxConcurrentRequests,
        int upstreamTimeoutMillis,
        String version) {

    public static final String REPO_URL = "https://github.com/matthewmgamble/sports-mcp";

    public static AppConfig fromEnv() {
        return fromMap(System.getenv());
    }

    /** Builds config from an arbitrary key/value source (tests pass their own map). */
    public static AppConfig fromMap(Map<String, String> env) {
        return new AppConfig(
                get(env, "BIND_HOST", "127.0.0.1"),
                Integer.parseInt(get(env, "PORT", "8080")),
                // Management port (metrics, admin) never faces the internet.
                get(env, "MGMT_BIND_HOST", "127.0.0.1"),
                Integer.parseInt(get(env, "MGMT_PORT", "9090")),
                get(env, "DB_PATH", "data/sports.db"),
                ZoneId.of(get(env, "DEFAULT_TIMEZONE", "America/New_York")),
                // Browser Origins allowed to call /mcp. Requests without an Origin header
                // (normal MCP clients) are always accepted; any other Origin is rejected.
                csv(get(env, "MCP_ALLOWED_ORIGINS", "")),
                // Empty = each league's default (all MVP leagues on).
                csv(get(env, "ENABLED_LEAGUES", "")),
                // Base URLs are overridable so tests can point providers at WireMock.
                get(env, "PROVIDER_ESPN_BASE_URL", "https://site.api.espn.com/apis/site/v2/sports"),
                get(env, "PROVIDER_NHL_BASE_URL", "https://api-web.nhle.com/v1"),
                get(env, "PROVIDER_MLB_BASE_URL", "https://statsapi.mlb.com/api/v1"),
                // Team/schedule sync and retention jobs. Tests turn this off.
                Boolean.parseBoolean(get(env, "BACKGROUND_SYNC", "true")),
                Integer.parseInt(get(env, "SYNC_HORIZON_DAYS", "14")),
                Integer.parseInt(get(env, "RETENTION_FINISHED_DAYS", "90")),
                // Bearer token for /admin/* on the management port. Blank disables admin.
                get(env, "ADMIN_TOKEN", ""),
                // Max data age before a request refreshes it (see FreshnessPolicy).
                Integer.parseInt(get(env, "FRESH_LIVE_SECONDS", "15")),
                Integer.parseInt(get(env, "FRESH_PREGAME_SECONDS", "60")),
                Integer.parseInt(get(env, "FRESH_RECENT_FINAL_SECONDS", "300")),
                Integer.parseInt(get(env, "FRESH_EMPTY_SECONDS", "120")),
                // Minimum gap between upstream fetches of the same scoreboard, even with
                // refresh=true.
                Integer.parseInt(get(env, "REFRESH_FLOOR_SECONDS", "5")),
                // Public-port protection (per client: API key if valid, else IP).
                Integer.parseInt(get(env, "RATE_LIMIT_PER_MIN", "60")),
                Integer.parseInt(get(env, "RATE_LIMIT_BURST", "20")),
                Integer.parseInt(get(env, "RATE_LIMIT_REFRESH_PER_MIN", "6")),
                Integer.parseInt(get(env, "RATE_LIMIT_KEYED_MULTIPLIER", "10")),
                // SHA-256 hex of issued API keys: echo -n "$KEY" | shasum -a 256
                csv(get(env, "API_KEYS_SHA256", "")),
                // Only peers in these ranges may set CF-Connecting-IP / X-Forwarded-For.
                csv(get(env, "TRUSTED_PROXY_CIDRS", "")),
                Integer.parseInt(get(env, "MAX_CONCURRENT_REQUESTS", "200")),
                Integer.parseInt(get(env, "UPSTREAM_TIMEOUT_MS", "5000")),
                AppConfig.class.getPackage().getImplementationVersion() != null
                        ? AppConfig.class.getPackage().getImplementationVersion()
                        : "dev");
    }

    /** Identifies us to upstreams, with a link so operators can find us. */
    public String userAgent() {
        return "sports-mcp/" + version + " (+" + REPO_URL + ")";
    }

    private static String get(Map<String, String> env, String key, String def) {
        String v = env.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static List<String> csv(String v) {
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
