package ca.hex42labs.sports.api.mcp;

import ca.hex42labs.sports.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the stateless Streamable HTTP MCP endpoint. Stateless suits a public server: every tool is
 * read-only, nothing is pushed to clients, so there is no per-session state to hold in memory.
 */
public final class McpServerFactory {

    public static final String ENDPOINT = "/mcp";

    private static final String INSTRUCTIONS =
            "Sports scores, schedules and team information for NFL, NHL, NBA, MLB, college"
                    + " football/basketball and major European soccer leagues. Times are UTC;"
                    + " scores are near-live and may lag the broadcast. Data is sourced from"
                    + " unofficial public endpoints (ESPN, NHL, MLB).";

    private McpServerFactory() {}

    /** The servlet transport plus the server bound to it; callers keep both for shutdown. */
    public record Mcp(
            HttpServletStatelessServerTransport transport, McpStatelessSyncServer server) {
        public void close() {
            server.close();
        }
    }

    public static Mcp create(AppConfig config, ObjectMapper json, Clock clock) {
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
                        // Run handlers on the servlet (virtual) thread rather than hopping to a
                        // Reactor scheduler: our handlers are plain blocking code.
                        .immediateExecution(true)
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                        .toolCall(pingTool(), (ctx, req) -> ping(config, clock))
                        .build();

        return new Mcp(transport, server);
    }

    // Sprint 0 spike tool: proves the transport end to end. Replaced by real tools in Sprint 4.
    private static McpSchema.Tool pingTool() {
        Map<String, Object> input = Map.of("type", "object", "properties", Map.of());
        Map<String, Object> output =
                Map.of(
                        "type", "object",
                        "properties",
                                Map.of(
                                        "server", Map.of("type", "string"),
                                        "version", Map.of("type", "string"),
                                        "time", Map.of("type", "string", "format", "date-time")),
                        "required", List.of("server", "version", "time"));
        return McpSchema.Tool.builder("sports_ping", input)
                .title("Ping")
                .description("Health check: returns the server name, version and current UTC time.")
                .outputSchema(output)
                .annotations(
                        McpSchema.ToolAnnotations.builder()
                                .readOnlyHint(true)
                                .idempotentHint(true)
                                .openWorldHint(false)
                                .build())
                .build();
    }

    private static McpSchema.CallToolResult ping(AppConfig config, Clock clock) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("server", "sports-mcp");
        result.put("version", config.version());
        result.put("time", Instant.now(clock).toString());
        return McpSchema.CallToolResult.builder()
                .structuredContent(result)
                .addTextContent(
                        "sports-mcp " + config.version() + " is up at " + result.get("time"))
                .build();
    }
}
