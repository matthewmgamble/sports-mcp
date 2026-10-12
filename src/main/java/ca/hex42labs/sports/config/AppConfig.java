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
