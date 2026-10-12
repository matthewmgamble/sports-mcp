package ca.hex42labs.sports;

import ca.hex42labs.sports.api.mcp.McpServerFactory;
import ca.hex42labs.sports.config.AppConfig;
import ca.hex42labs.sports.persistence.Database;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import java.time.Clock;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point and manual wiring (no DI container, same approach as Pulseboard). */
public final class App implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(App.class);

    private final AppConfig config;
    private final Database db;
    private final McpServerFactory.Mcp mcp;
    private final Javalin http;

    public App(AppConfig config, Clock clock) {
        this.config = config;
        ObjectMapper json = objectMapper();
        this.db = Database.open(config.dbPath());
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
