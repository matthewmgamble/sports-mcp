package ca.hex42labs.sports.api.http;

import ca.hex42labs.sports.observability.SportsMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Front door for the public port: a concurrency cap, per-client rate limits for {@code /api/*} and
 * MCP tool calls, request context for downstream refresh limits, metrics, and an access log that
 * records a salted hash of the client IP rather than the IP itself.
 *
 * <p>For MCP, the JSON-RPC body is peeked (it's at most 64 KB) so a rate-limited {@code tools/call}
 * gets a normal tool error the agent can relay, instead of an HTTP 429 many MCP clients would treat
 * as a transport failure. {@code initialize} and {@code tools/list} are not counted.
 */
public final class PublicTrafficFilter implements Filter {

    private static final Logger access = LoggerFactory.getLogger("access");
    private static final Logger log = LoggerFactory.getLogger(PublicTrafficFilter.class);
    static final int MAX_MCP_BODY = 64 * 1024;

    private final ClientIdentity identity;
    private final RateLimits limits;
    private final Semaphore inFlight;
    private final ObjectMapper json;
    private final IpHasher hasher = new IpHasher();

    public PublicTrafficFilter(
            ClientIdentity identity, RateLimits limits, int maxConcurrent, ObjectMapper json) {
        this.identity = identity;
        this.limits = limits;
        this.inFlight = new Semaphore(maxConcurrent);
        this.json = json;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();
        String iface = path.equals("/mcp") ? "mcp" : path.startsWith("/api/") ? "rest" : "other";
        if (iface.equals("other")) {
            chain.doFilter(request, response); // health, landing page, openapi
            return;
        }
        long start = System.nanoTime();
        if (!inFlight.tryAcquire()) {
            SportsMetrics.overloaded();
            writeError(res, 503, "overloaded", "Server is busy; retry in a few seconds.", 2);
            return;
        }
        ClientIdentity.Client client = identity.resolve(req);
        RequestContext.set(client);
        HttpServletRequest forward = req;
        String detail = "";
        try {
            if (iface.equals("mcp") && "POST".equals(req.getMethod())) {
                byte[] body = req.getInputStream().readNBytes(MAX_MCP_BODY + 1);
                if (body.length > MAX_MCP_BODY) {
                    writeError(res, 413, "invalid_argument", "Request body too large.", 0);
                    return;
                }
                forward = new CachedBodyRequest(req, body);
                JsonNode rpc = parse(body);
                String method = rpc == null ? null : rpc.path("method").asText(null);
                detail =
                        method == null
                                ? ""
                                : method
                                        + (rpc.path("params").has("name")
                                                ? " " + rpc.path("params").path("name").asText()
                                                : "");
                if ("tools/call".equals(method)) {
                    long wait = limits.tryAcquire(client, RateLimits.Scope.GENERAL);
                    if (wait > 0) {
                        SportsMetrics.rateLimited("general", "mcp");
                        writeRpcToolError(res, rpc.get("id"), retrySeconds(wait));
                        return;
                    }
                }
            } else if (iface.equals("rest")) {
                long wait = limits.tryAcquire(client, RateLimits.Scope.GENERAL);
                if (wait > 0) {
                    SportsMetrics.rateLimited("general", "rest");
                    writeError(
                            res,
                            429,
                            "rate_limited",
                            "Too many requests; retry after " + retrySeconds(wait) + "s.",
                            retrySeconds(wait));
                    return;
                }
            }
            chain.doFilter(forward, response);
        } finally {
            RequestContext.clear();
            inFlight.release();
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            SportsMetrics.httpRequest(iface, res.getStatus(), took);
            access.info(
                    "{} {} {} {} {}ms client={}{}",
                    iface,
                    req.getMethod(),
                    path,
                    res.getStatus(),
                    took.toMillis(),
                    hasher.hash(client.ip()),
                    detail.isEmpty() ? "" : " " + detail);
        }
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode n = json.readTree(body);
            return n != null && n.isObject() ? n : null;
        } catch (IOException e) {
            return null; // let the MCP transport produce its own parse error
        }
    }

    private static long retrySeconds(long waitNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos) + 1);
    }

    private void writeError(
            HttpServletResponse res, int status, String code, String message, long retryAfter)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        if (retryAfter > 0) {
            res.setHeader("Retry-After", String.valueOf(retryAfter));
        }
        ObjectNode body = json.createObjectNode();
        body.putObject("error").put("code", code).put("message", message);
        res.getOutputStream().write(json.writeValueAsBytes(body));
    }

    /** A JSON-RPC success carrying {@code isError: true}, as a tool failure the agent can read. */
    private void writeRpcToolError(HttpServletResponse res, JsonNode id, long retryAfter)
            throws IOException {
        res.setStatus(200);
        res.setContentType("application/json");
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        ObjectNode body = json.createObjectNode().put("jsonrpc", "2.0");
        body.set("id", id);
        ObjectNode result = body.putObject("result");
        result.put("isError", true);
        result.putArray("content")
                .addObject()
                .put("type", "text")
                .put(
                        "text",
                        "rate_limited: too many requests from this client; retry in "
                                + retryAfter
                                + "s.");
        res.getOutputStream().write(json.writeValueAsBytes(body));
    }

    /**
     * HMAC of the IP with a random key that rotates daily and never leaves memory, so logs can
     * correlate a client within a day but can't be reversed to an address.
     */
    static final class IpHasher {
        private final SecureRandom random = new SecureRandom();
        private LocalDate day;
        private Mac mac;

        synchronized String hash(String ip) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(day)) {
                byte[] key = new byte[32];
                random.nextBytes(key);
                try {
                    mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(key, "HmacSHA256"));
                } catch (Exception e) {
                    log.error("Cannot initialise IP hashing", e);
                    return "unknown";
                }
                day = today;
            }
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(String.valueOf(ip).getBytes(StandardCharsets.UTF_8)), 0, 6);
        }
    }

    /** Lets the MCP servlet read a body we've already consumed. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous only");
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
