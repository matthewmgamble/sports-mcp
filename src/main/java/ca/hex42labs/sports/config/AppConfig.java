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
        String version) {

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
                AppConfig.class.getPackage().getImplementationVersion() != null
                        ? AppConfig.class.getPackage().getImplementationVersion()
                        : "dev");
    }

    private static String get(Map<String, String> env, String key, String def) {
        String v = env.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static List<String> csv(String v) {
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
