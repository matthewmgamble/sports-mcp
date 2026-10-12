package ca.hex42labs.sports;

import ca.hex42labs.sports.api.Schemas;
import ca.hex42labs.sports.api.SportsService;
import ca.hex42labs.sports.api.admin.ManagementServer;
import ca.hex42labs.sports.api.mcp.McpServerFactory;
import ca.hex42labs.sports.api.rest.RestRoutes;
import ca.hex42labs.sports.application.FreshnessPolicy;
import ca.hex42labs.sports.application.ScheduleSync;
import ca.hex42labs.sports.application.ScoreRefreshCoordinator;
import ca.hex42labs.sports.application.SyncPlanner;
import ca.hex42labs.sports.application.TeamAliases;
import ca.hex42labs.sports.application.TeamCatalog;
import ca.hex42labs.sports.application.TeamResolver;
import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.persistence.Database;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.ResilientProvider;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.provider.espn.EspnProvider;
import ca.hex42labs.sports.provider.mlb.MlbProvider;
import ca.hex42labs.sports.provider.nhl.NhlProvider;
import ca.hex42labs.sports.scheduler.BackgroundJobs;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point and manual wiring (no DI container, same approach as Pulseboard). */
public final class App implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final AppConfig config;
    private final Database db;
    private final LeagueRegistry leagues;
    private final TeamCatalog teams;
    private final TeamResolver teamResolver;
    private final GameRepository games;
    private final ScheduleSync schedules;
    private final List<ResilientProvider> resilient;
    private final ScoreRefreshCoordinator scores;
    private final SportsService sports;
    private final ManagementServer management;
    private final BackgroundJobs jobs;
    private final McpServerFactory.Mcp mcp;
    private final Javalin http;

    public App(AppConfig config, Clock clock) {
        this.config = config;
        ObjectMapper json = objectMapper();
        this.db = Database.open(config.dbPath());
        this.leagues = LeagueRegistry.standard(config.enabledLeagues());

        UpstreamHttp http = UpstreamHttp.withDefaults(json, config.userAgent());
        // Every upstream call (background or on-demand) goes through a rate limiter and breaker.
        this.resilient =
                List.of(
                        ResilientProvider.wrap(new EspnProvider(http, config.espnBaseUrl(), clock)),
                        ResilientProvider.wrap(new NhlProvider(http, config.nhlBaseUrl(), clock)),
                        ResilientProvider.wrap(new MlbProvider(http, config.mlbBaseUrl(), clock)));
        Providers providers = new Providers(List.copyOf(resilient));
        this.games = new GameRepository(db, json);
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
        this.sports =
                new SportsService(
                        leagues,
                        teams,
                        teamResolver,
                        games,
                        scores,
                        clock,
                        config.defaultTimezone());
        this.mcp = McpServerFactory.create(config, json, sports);
        Map<String, Object> openApi = RestRoutes.openApi(Schemas.bundled(), config.version());
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
                        resilient);

        this.http =
                Javalin.create(
                        cfg -> {
                            cfg.jsonMapper(new JavalinJackson(json, false));
                            cfg.startup.showJavalinBanner = false;
                            cfg.jetty.host = config.bindHost();
                            cfg.jetty.port = config.port();
                            cfg.concurrency.useVirtualThreads = true;
                            // MCP servlet sits beside Javalin's catch-all "/*"; the exact
                            // "/mcp" mapping wins per the servlet spec.
                            cfg.jetty.modifyServletContextHandler(
                                    h ->
                                            h.addServlet(
                                                    new ServletHolder(mcp.transport()),
                                                    McpServerFactory.ENDPOINT));

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

    public App start() {
        http.start();
        management.start();
        if (jobs != null) {
            // Off the startup path: serve whatever is persisted while syncs catch up.
            jobs.start();
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

    public SportsService sports() {
        return sports;
    }

    public ScoreRefreshCoordinator scores() {
        return scores;
    }

    public List<ResilientProvider> providers() {
        return resilient;
    }

    public int managementPort() {
        return management.port();
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
        db.close();
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static void main(String[] args) {
        App app = new App(AppConfig.fromEnv(), Clock.systemUTC()).start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }
}
