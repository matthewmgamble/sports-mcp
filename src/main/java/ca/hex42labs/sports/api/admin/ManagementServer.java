package ca.hex42labs.sports.api.admin;

import ca.hex42labs.sports.application.ScheduleSync;
import ca.hex42labs.sports.application.TeamCatalog;
import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.SyncState;
import ca.hex42labs.sports.observability.MetricsSetup;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.provider.ResilientProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.json.JavalinJackson;
import io.micrometer.core.instrument.Gauge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operator endpoints on a separate listener (localhost by default), never on the public port.
 *
 * <pre>
 *   GET  /health/live
 *   GET  /admin/sync-state                         sync bookkeeping + game counts
 *   POST /admin/resync?league=nfl                  full schedule window now
 *   POST /admin/resync?league=nfl&from=..&to=..    explicit window (max 62 days)
 *   POST /admin/resync?league=nfl&kind=teams       team catalog now
 * </pre>
 *
 * Admin calls need {@code Authorization: Bearer $ADMIN_TOKEN}; with no token configured they're
 * disabled entirely.
 */
public final class ManagementServer implements AutoCloseable {

    static final int MAX_WINDOW_DAYS = 62;

    private final Javalin http;
    private final String host;

    public ManagementServer(
            String host,
            int port,
            String adminToken,
            ObjectMapper json,
            LeagueRegistry leagues,
            TeamCatalog teams,
            ScheduleSync schedules,
            SyncStateRepository syncState,
            GameRepository games,
            java.util.List<ResilientProvider> providers,
            MetricsSetup metrics) {
        this.host = host;
        byte[] token = adminToken.getBytes(StandardCharsets.UTF_8);
        registerGauges(metrics, leagues, syncState, providers);
        this.http =
                Javalin.create(
                        cfg -> {
                            cfg.jsonMapper(new JavalinJackson(json, false));
                            cfg.startup.showJavalinBanner = false;
                            cfg.startup.showOldJavalinVersionWarning = false;
                            cfg.jetty.host = host;
                            cfg.jetty.port = port;
                            cfg.routes.get("/health/live", ctx -> ctx.json(Map.of("status", "UP")));
                            // Prometheus scrape (no auth: this listener is localhost/private only).
                            cfg.routes.get(
                                    "/metrics",
                                    ctx ->
                                            ctx.contentType(
                                                            "text/plain; version=0.0.4; charset=utf-8")
                                                    .result(metrics.scrape()));
                            cfg.routes.before("/admin/*", ctx -> authorize(ctx, token));
                            cfg.routes.get(
                                    "/admin/sync-state",
                                    ctx ->
                                            ctx.json(
                                                    Map.of(
                                                            "syncState", syncState.findAll(),
                                                            "gamesByLeague", games.countByLeague(),
                                                            "teams", teams.all().size(),
                                                            "circuitBreakers",
                                                                    breakers(providers))));
                            cfg.routes.post(
                                    "/admin/resync",
                                    ctx -> ctx.json(resync(ctx, leagues, teams, schedules)));
                        });
    }

    /**
     * Gauges computed at scrape time from persisted state: how old each league's last good sync is
     * (alert when schedule age grows) and each provider's breaker state.
     */
    private static void registerGauges(
            MetricsSetup metrics,
            LeagueRegistry leagues,
            SyncStateRepository syncState,
            java.util.List<ResilientProvider> providers) {
        for (var league : leagues.enabled()) {
            for (SyncState.Kind kind : SyncState.Kind.values()) {
                Gauge.builder(
                                "sports_sync_age_seconds",
                                () -> syncAge(syncState, league.id(), kind))
                        .description("Seconds since the last successful sync (-1 = never)")
                        .tag("league", league.id())
                        .tag("kind", kind.name())
                        .register(metrics.registry());
            }
        }
        for (ResilientProvider p : providers) {
            Gauge.builder("sports_circuit_state", () -> breakerValue(p.breaker().getState()))
                    .description("0 closed, 1 half-open, 2 open")
                    .tag("provider", p.id())
                    .register(metrics.registry());
        }
    }

    private static double syncAge(
            SyncStateRepository syncState, String league, SyncState.Kind kind) {
        return syncState
                .find(league, kind)
                .map(SyncState::lastSuccessAt)
                .map(t -> (double) Duration.between(t, Instant.now()).toSeconds())
                .orElse(-1.0);
    }

    private static double breakerValue(CircuitBreaker.State s) {
        return switch (s) {
            case CLOSED, DISABLED, METRICS_ONLY -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
        };
    }

    public ManagementServer start() {
        http.start();
        return this;
    }

    public int port() {
        return http.port();
    }

    private static Map<String, String> breakers(java.util.List<ResilientProvider> providers) {
        Map<String, String> out = new LinkedHashMap<>();
        providers.forEach(p -> out.put(p.id(), p.breaker().getState().name()));
        return out;
    }

    private static void authorize(Context ctx, byte[] token) {
        if (token.length == 0) {
            throw new ForbiddenResponse("Admin API disabled: set ADMIN_TOKEN");
        }
        String header = ctx.header("Authorization");
        byte[] presented =
                header != null && header.startsWith("Bearer ")
                        ? header.substring(7).getBytes(StandardCharsets.UTF_8)
                        : new byte[0];
        if (!MessageDigest.isEqual(token, presented)) {
            throw new UnauthorizedResponse("Bad or missing admin token");
        }
    }

    private static Object resync(
            Context ctx, LeagueRegistry leagues, TeamCatalog teams, ScheduleSync schedules) {
        String leagueId = ctx.queryParam("league");
        if (leagueId == null) {
            throw new BadRequestResponse("league is required. Enabled: " + leagues.enabledIds());
        }
        League league =
                leagues.find(leagueId)
                        .orElseThrow(
                                () ->
                                        new NotFoundResponse(
                                                "Unknown or disabled league " + leagueId));
        String kind = ctx.queryParamAsClass("kind", String.class).getOrDefault("schedule");
        if (kind.equals("teams")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("league", league.id());
            out.put("kind", "TEAMS");
            out.put("ok", teams.sync(league));
            out.put("teams", teams.byLeague(league.id()).size());
            return out;
        }
        if (!kind.equals("schedule")) {
            throw new BadRequestResponse("kind must be schedule or teams");
        }
        String from = ctx.queryParam("from");
        String to = ctx.queryParam("to");
        if (from == null && to == null) {
            return schedules.full(league, true);
        }
        if (from == null || to == null) {
            throw new BadRequestResponse("Give both from and to (YYYY-MM-DD), or neither");
        }
        LocalDate start = date(from);
        LocalDate end = date(to);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_WINDOW_DAYS) {
            throw new BadRequestResponse(
                    "Window must be 1-" + MAX_WINDOW_DAYS + " days, from <= to");
        }
        return schedules.sync(league, SyncState.Kind.SCHEDULE, start, end, true);
    }

    private static LocalDate date(String s) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new BadRequestResponse("Bad date " + s + " (want YYYY-MM-DD)");
        }
    }

    @Override
    public void close() {
        http.stop(); // safe on a server that never started
    }
}
