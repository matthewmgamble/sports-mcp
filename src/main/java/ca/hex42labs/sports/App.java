package ca.hex42labs.sports;

import ca.hex42labs.sports.api.mcp.McpServerFactory;
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
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.provider.espn.EspnProvider;
import ca.hex42labs.sports.provider.mlb.MlbProvider;
import ca.hex42labs.sports.provider.nhl.NhlProvider;
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

    /** Team lists change a few times a year; refresh daily. */
    static final Duration TEAM_SYNC_MAX_AGE = Duration.ofHours(24);

    private final AppConfig config;
    private final Database db;
    private final LeagueRegistry leagues;
    private final TeamCatalog teams;
    private final TeamResolver teamResolver;
    private final GameRepository games;
    private final McpServerFactory.Mcp mcp;
    private final Javalin http;

    public App(AppConfig config, Clock clock) {
        this.config = config;
        ObjectMapper json = objectMapper();
        this.db = Database.open(config.dbPath());
        this.leagues = LeagueRegistry.standard(config.enabledLeagues());

        UpstreamHttp http = UpstreamHttp.withDefaults(json, config.userAgent());
        Providers providers =
                new Providers(
                        List.of(
                                new EspnProvider(http, config.espnBaseUrl(), clock),
                                new NhlProvider(http, config.nhlBaseUrl()),
                                new MlbProvider(http, config.mlbBaseUrl())));
        this.games = new GameRepository(db, json);
        this.teams =
                new TeamCatalog(
                        leagues,
                        providers,
                        new TeamRepository(db, json),
                        new SyncStateRepository(db),
                        TeamAliases.bundled(),
                        clock);
        this.teams.load();
        this.teamResolver = new TeamResolver(teams);

        this.mcp = McpServerFactory.create(config, json, clock);

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
                        });
    }

    public App start() {
        http.start();
        if (config.syncOnStartup()) {
            // Off the startup path: serve whatever is persisted while catalogs refresh.
            Thread.ofVirtual().name("team-sync").start(() -> teams.syncStale(TEAM_SYNC_MAX_AGE));
        }
        log.info(
                "sports-mcp {} listening on {}:{} (MCP at {})",
                config.version(),
                config.bindHost(),
                port(),
                McpServerFactory.ENDPOINT);
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

    @Override
    public void close() {
        http.stop();
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
