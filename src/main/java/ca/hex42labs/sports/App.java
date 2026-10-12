package ca.hex42labs.sports;

import ca.hex42labs.sports.api.Schemas;
import ca.hex42labs.sports.api.SportsService;
import ca.hex42labs.sports.api.admin.ManagementServer;
import ca.hex42labs.sports.api.http.ClientIdentity;
import ca.hex42labs.sports.api.http.PublicTrafficFilter;
import ca.hex42labs.sports.api.http.RateLimits;
import ca.hex42labs.sports.api.http.RequestContext;
import ca.hex42labs.sports.api.mcp.McpServerFactory;
import ca.hex42labs.sports.api.rest.RestRoutes;
import ca.hex42labs.sports.application.FreshnessPolicy;
import ca.hex42labs.sports.application.GameLinks;
import ca.hex42labs.sports.application.ScheduleSync;
import ca.hex42labs.sports.application.ScoreRefreshCoordinator;
import ca.hex42labs.sports.application.SyncPlanner;
import ca.hex42labs.sports.application.TeamAliases;
import ca.hex42labs.sports.application.TeamCatalog;
import ca.hex42labs.sports.application.TeamResolver;
import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.observability.MetricsSetup;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.GameLinkRepository;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.ResilientProvider;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.provider.espn.EspnProvider;
import ca.hex42labs.sports.provider.mlb.MlbProvider;
import ca.hex42labs.sports.provider.mlb.MlbWithEspnFallback;
import ca.hex42labs.sports.provider.nhl.NhlProvider;
import ca.hex42labs.sports.scheduler.BackgroundJobs;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point and manual wiring (no DI container, same approach as Pulseboard). */
public final class App implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final AppConfig config;
    private final Database db;
    private final MetricsSetup metrics;
    private final LeagueRegistry leagues;
    private final List<ResilientProvider> resilient;
    private final GameRepository games;
    private final TeamCatalog teams;
    private final TeamResolver teamResolver;
    private final ScheduleSync schedules;
    private final ScoreRefreshCoordinator scores;
    private final SportsService sports;
    private final McpServerFactory.Mcp mcp;
    private final BackgroundJobs jobs;
    private final ManagementServer management;
    private final Javalin http;

    public App(AppConfig config, Clock clock) {
        this.config = config;
        ObjectMapper json = objectMapper();
        this.db = Database.open(config.dbPath());
        this.metrics = MetricsSetup.prometheus();
        this.leagues = LeagueRegistry.standard(config.enabledLeagues());

        // ---- upstream providers: every call (background or on-demand) is rate-limited and
        // circuit-broken per provider.
        UpstreamHttp upstream =
                new UpstreamHttp(
                        json,
                        config.userAgent(),
                        Duration.ofSeconds(3),
                        Duration.ofMillis(config.upstreamTimeoutMillis()));
        ResilientProvider espn =
                ResilientProvider.wrap(new EspnProvider(upstream, config.espnBaseUrl(), clock));
        ResilientProvider nhl =
                ResilientProvider.wrap(new NhlProvider(upstream, config.nhlBaseUrl(), clock));
        ResilientProvider mlb =
                ResilientProvider.wrap(new MlbProvider(upstream, config.mlbBaseUrl(), clock));
        this.resilient = List.of(espn, nhl, mlb);
        this.games = new GameRepository(db, json);
        // MLB: statsapi first (live scores, linescores); ESPN fills days statsapi refuses, linked
        // onto statsapi's game ids.
        Providers providers =
                new Providers(
                        List.of(
                                espn,
                                nhl,
                                new MlbWithEspnFallback(
                                        mlb,
                                        espn,
                                        new GameLinks(games, new GameLinkRepository(db), clock),
                                        clock)));

        // ---- data and domain services
        SyncStateRepository syncState = new SyncStateRepository(db);
        this.teams =
                new TeamCatalog(
                        leagues,
                        providers,
                        new TeamRepository(db, json),
                        syncState,
                        TeamAliases.bundled(),
                        clock);
        this.teams.load();
        this.teamResolver = new TeamResolver(teams);
        this.schedules =
                new ScheduleSync(
                        providers, games, teams, syncState, clock, config.syncHorizonDays());
        this.scores =
                new ScoreRefreshCoordinator(
                        leagues,
                        providers,
                        games,
                        teams,
                        new FreshnessPolicy(
                                new FreshnessPolicy.Settings(
                                        Duration.ofSeconds(config.freshLiveSeconds()),
                                        Duration.ofSeconds(config.freshPregameSeconds()),
                                        Duration.ofSeconds(30),
                                        Duration.ofSeconds(config.freshRecentFinalSeconds()),
                                        Duration.ofSeconds(config.freshEmptySeconds()))),
                        clock,
                        new ScoreRefreshCoordinator.Settings(
                                Duration.ofSeconds(config.refreshFloorSeconds()),
                                Duration.ofSeconds(6),
                                config.syncHorizonDays()));

        // ---- public interfaces
        RateLimits rateLimits =
                new RateLimits(
                        new RateLimits.Settings(
                                config.rateLimitPerMinute(),
                                config.rateLimitBurst(),
                                config.rateLimitRefreshPerMinute(),
                                config.rateLimitKeyedMultiplier()));
        // refresh=true has its own per-caller budget; internal calls (no request) always pass.
        SportsService.RefreshGate refreshGate =
                () ->
                        RequestContext.current()
                                .map(c -> rateLimits.tryAcquire(c, RateLimits.Scope.REFRESH) == 0)
                                .orElse(true);
        this.sports =
                new SportsService(
                        leagues,
                        teams,
                        teamResolver,
                        games,
                        scores,
                        clock,
                        config.defaultTimezone(),
                        refreshGate);
        this.mcp = McpServerFactory.create(config, json, sports);
        PublicTrafficFilter traffic =
                new PublicTrafficFilter(
                        new ClientIdentity(config.trustedProxyCidrs(), config.apiKeysSha256()),
                        rateLimits,
                        config.maxConcurrentRequests(),
                        json);
        Map<String, Object> openApi = RestRoutes.openApi(Schemas.bundled(), config.version());
        String landingPage = resource("/public/index.html");

        // ---- background work and operator listener
        this.jobs =
                config.backgroundSync()
                        ? new BackgroundJobs(
                                leagues,
                                teams,
                                schedules,
                                new SyncPlanner(schedules, clock),
                                games,
                                clock,
                                Duration.ofDays(config.retentionFinishedDays()))
                        : null;
        this.management =
                new ManagementServer(
                        config.mgmtBindHost(),
                        config.mgmtPort(),
                        config.adminToken(),
                        json,
                        leagues,
                        teams,
                        schedules,
                        syncState,
                        games,
                        resilient,
                        metrics);

        this.http =
                Javalin.create(
                        cfg -> {
                            cfg.jsonMapper(new JavalinJackson(json, false));
                            cfg.startup.showJavalinBanner = false;
                            cfg.startup.showOldJavalinVersionWarning = false;
                            cfg.jetty.host = config.bindHost();
                            cfg.jetty.port = config.port();
                            cfg.concurrency.useVirtualThreads = true;
                            cfg.jetty.modifyServletContextHandler(
                                    h -> {
                                        // MCP servlet sits beside Javalin's catch-all "/*"; the
                                        // exact "/mcp" mapping wins per the servlet spec.
                                        h.addServlet(
                                                new ServletHolder(mcp.transport()),
                                                McpServerFactory.ENDPOINT);
                                        // Rate limits, concurrency cap and access log for both.
                                        h.addFilter(
                                                new FilterHolder(traffic),
                                                "/*",
                                                EnumSet.of(DispatcherType.REQUEST));
                                    });
                            // Don't advertise the server stack; bound header size.
                            cfg.jetty.modifyHttpConfiguration(
                                    hc -> {
                                        hc.setSendServerVersion(false);
                                        hc.setRequestHeaderSize(16 * 1024);
                                    });

                            cfg.routes.get(
                                    "/",
                                    ctx ->
                                            ctx.contentType("text/html; charset=utf-8")
                                                    .result(landingPage));
                            cfg.routes.get("/health/live", ctx -> ctx.json(Map.of("status", "UP")));
                            cfg.routes.get(
                                    "/health/ready",
                                    ctx -> {
                                        boolean ok = db.isHealthy();
                                        ctx.status(ok ? 200 : 503)
                                                .json(Map.of("status", ok ? "UP" : "DOWN"));
                                    });
                            RestRoutes.mount(cfg.routes, sports, openApi);
                        });
    }

    /**
     * Starts everything or nothing: a half-started process (say, public port up but management port
     * taken) would serve traffic with no metrics, admin or background sync, so any failure tears
     * down what started and rethrows.
     */
    public App start() {
        try {
            management.start(); // private listener first: fail before exposing anything
            http.start();
            if (jobs != null) {
                // Off the startup path: serve whatever is persisted while syncs catch up.
                jobs.start();
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
        log.info(
                "sports-mcp {} listening on {}:{} (MCP at {}), management on {}:{}",
                config.version(),
                config.bindHost(),
                port(),
                McpServerFactory.ENDPOINT,
                config.mgmtBindHost(),
                management.port());
        return this;
    }

    /** Actual bound port (differs from config when PORT=0 in tests). */
    public int port() {
        return http.port();
    }

    public int managementPort() {
        return management.port();
    }

    public LeagueRegistry leagues() {
        return leagues;
    }

    public TeamCatalog teams() {
        return teams;
    }

    public TeamResolver teamResolver() {
        return teamResolver;
    }

    public GameRepository games() {
        return games;
    }

    public ScheduleSync schedules() {
        return schedules;
    }

    public ScoreRefreshCoordinator scores() {
        return scores;
    }

    public SportsService sports() {
        return sports;
    }

    public List<ResilientProvider> providers() {
        return resilient;
    }

    public MetricsSetup metrics() {
        return metrics;
    }

    @Override
    public void close() {
        if (jobs != null) {
            jobs.close();
        }
        management.close();
        http.stop();
        scores.close();
        mcp.close();
        metrics.close();
        db.close();
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static String resource(String path) {
        try (InputStream in = App.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        try {
            App app = new App(AppConfig.fromEnv(), Clock.systemUTC()).start();
            Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
        } catch (RuntimeException e) {
            log.error("Startup failed: {}", e.getMessage(), e);
            System.exit(1); // Jetty threads are non-daemon; don't linger half-started
        }
    }
}
