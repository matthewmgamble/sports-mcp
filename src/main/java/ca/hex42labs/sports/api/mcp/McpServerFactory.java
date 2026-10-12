package ca.hex42labs.sports.api.mcp;

import ca.hex42labs.sports.api.ApiException;
import ca.hex42labs.sports.api.Schemas;
import ca.hex42labs.sports.api.SportsService;
import ca.hex42labs.sports.config.AppConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stateless Streamable HTTP MCP endpoint. Stateless suits a public server: every tool is
 * read-only and nothing is pushed to clients, so there's no per-session state to hold.
 *
 * <p>Tool names, descriptions and input schemas come from {@code api/tools.json}; output schemas
 * from {@code api/schemas.json}. Each tool returns the same envelope REST does, as structured
 * content, plus a short text summary.
 */
public final class McpServerFactory {

    public static final String ENDPOINT = "/mcp";
    private static final Logger log = LoggerFactory.getLogger(McpServerFactory.class);

    private static final String INSTRUCTIONS =
            "Sports scores, schedules and teams for NFL, NHL, NBA, MLB, NCAA football and men's"
                    + " basketball, Premier League, La Liga and Bundesliga. Team arguments accept ids"
                    + " (nhl:tor) or names ('Maple Leafs'); use sports_list_teams when a name is"
                    + " ambiguous. Times are UTC with localStart in the requested timezone. Scores"
                    + " are near-live; check meta.isStale. Data comes from unofficial public"
                    + " endpoints (ESPN, NHL, MLB).";

    private McpServerFactory() {}

    /** The servlet transport plus the server bound to it; callers keep both for shutdown. */
    public record Mcp(
            HttpServletStatelessServerTransport transport, McpStatelessSyncServer server) {
        public void close() {
            server.close();
        }
    }

    public static Mcp create(AppConfig config, ObjectMapper json, SportsService sports) {
        var mapper = new JacksonMcpJsonMapper(json);
        var transport =
                HttpServletStatelessServerTransport.builder()
                        .jsonMapper(mapper)
                        .messageEndpoint(ENDPOINT)
                        .maxRequestSize(64 * 1024)
                        .securityValidator(
                                DefaultServerTransportSecurityValidator.builder()
                                        .allowedOrigins(config.mcpAllowedOrigins())
                                        .build())
                        .build();

        var server =
                McpServer.sync(transport)
                        .serverInfo("sports-mcp", config.version())
                        .instructions(INSTRUCTIONS)
                        .jsonMapper(mapper)
                        .jsonSchemaValidator(new DefaultJsonSchemaValidator(json))
                        .validateToolInputs(true)
                        // Run handlers on the servlet (virtual) thread: our handlers block.
                        .immediateExecution(true)
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                        .tools(tools(json, sports))
                        .build();
        return new Mcp(transport, server);
    }

    /** Builds the tool list; package-visible so tests can inspect definitions. */
    @SuppressWarnings("unchecked")
    static List<McpStatelessServerFeatures.SyncToolSpecification> tools(
            ObjectMapper json, SportsService sports) {
        Schemas schemas = Schemas.bundled();
        Map<String, Function<Args, ToolResult>> handlers = handlers(sports);
        List<McpStatelessServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        for (Object o : (List<Object>) Schemas.load("/api/tools.json").get("tools")) {
            Map<String, Object> def = (Map<String, Object>) o;
            String name = (String) def.get("name");
            Function<Args, ToolResult> handler = handlers.get(name);
            if (handler == null) {
                throw new IllegalStateException("No handler for tool " + name);
            }
            McpSchema.Tool tool =
                    McpSchema.Tool.builder(name, (Map<String, Object>) def.get("inputSchema"))
                            .title((String) def.get("title"))
                            .description((String) def.get("description"))
                            .outputSchema(schemas.envelope((String) def.get("output")))
                            .annotations(
                                    McpSchema.ToolAnnotations.builder()
                                            .title((String) def.get("title"))
                                            .readOnlyHint(true)
                                            .destructiveHint(false)
                                            .idempotentHint(true)
                                            .openWorldHint(true)
                                            .build())
                            .build();
            specs.add(
                    new McpStatelessServerFeatures.SyncToolSpecification(
                            tool,
                            (ctx, req) -> call(json, name, handler, new Args(req.arguments()))));
        }
        if (specs.size() != handlers.size()) {
            throw new IllegalStateException("tools.json and handlers disagree");
        }
        return specs;
    }

    /** Envelope (as structured content) plus summary text. */
    record ToolResult(Object envelope, String summary) {}

    private static Map<String, Function<Args, ToolResult>> handlers(SportsService s) {
        return Map.of(
                "sports_list_leagues",
                a -> {
                    var e = s.leagues();
                    return new ToolResult(e, McpSummaries.leagues(e));
                },
                "sports_list_teams",
                a -> {
                    var e = s.teams(a.str("league"), a.str("search"), a.integer("limit"));
                    return new ToolResult(e, McpSummaries.teams(e));
                },
                "sports_get_games",
                a -> {
                    var e =
                            s.games(
                                    a.str("league"),
                                    a.str("team"),
                                    a.str("date"),
                                    a.str("from"),
                                    a.str("to"),
                                    a.str("status"),
                                    a.str("timezone"),
                                    a.integer("limit"));
                    return new ToolResult(
                            e,
                            McpSummaries.games(
                                    "%d game(s):",
                                    e, "No games found for that league/team and date range."));
                },
                "sports_get_live_scores",
                a -> {
                    var e =
                            s.live(
                                    a.str("league"),
                                    a.str("team"),
                                    a.bool("refresh"),
                                    a.str("timezone"));
                    return new ToolResult(
                            e,
                            McpSummaries.games(
                                    "%d live game(s):", e, "No games in progress right now."));
                },
                "sports_get_game",
                a -> {
                    var e = s.game(a.str("gameId"), a.bool("refresh"), a.str("timezone"));
                    return new ToolResult(e, McpSummaries.game(e));
                },
                "sports_get_team_schedule",
                a -> {
                    var e =
                            s.teamSchedule(
                                    a.str("team"),
                                    a.str("league"),
                                    a.str("from"),
                                    a.str("to"),
                                    a.integer("limit"),
                                    a.str("timezone"));
                    return new ToolResult(
                            e,
                            McpSummaries.games(
                                    "%d game(s):", e, "No games found for that team and range."));
                });
    }

    private static McpSchema.CallToolResult call(
            ObjectMapper json, String name, Function<Args, ToolResult> handler, Args args) {
        try {
            ToolResult r = handler.apply(args);
            // Same serialisation as REST, so structured content and REST bodies are identical.
            Map<String, Object> structured =
                    json.convertValue(r.envelope(), new TypeReference<>() {});
            return McpSchema.CallToolResult.builder()
                    .structuredContent(structured)
                    .addTextContent(r.summary())
                    .build();
        } catch (ApiException e) {
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent(e.code() + ": " + e.getMessage())
                    .build();
        } catch (RuntimeException e) {
            log.error("Tool {} failed", name, e);
            return McpSchema.CallToolResult.builder()
                    .isError(true)
                    .addTextContent(
                            "internal: something went wrong on our side; try again shortly.")
                    .build();
        }
    }

    /** Typed access to tool arguments (JSON numbers may arrive as any Number). */
    record Args(Map<String, Object> raw) {
        Args {
            raw = raw == null ? Map.of() : raw;
        }

        String str(String key) {
            Object v = raw.get(key);
            return v == null ? null : v.toString();
        }

        Integer integer(String key) {
            Object v = raw.get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Number n) {
                return n.intValue();
            }
            try {
                return Integer.valueOf(v.toString().trim());
            } catch (NumberFormatException e) {
                throw ApiException.invalid(key + " must be a whole number.");
            }
        }

        Boolean bool(String key) {
            Object v = raw.get(key);
            return v == null ? null : v instanceof Boolean b ? b : Boolean.valueOf(v.toString());
        }
    }
}
