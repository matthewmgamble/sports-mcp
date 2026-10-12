package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.observability.SportsMetrics;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * Shared JSON-over-HTTP client for all providers.
 *
 * <p>Handles the upstream quirks recorded in docs/upstream-contract.md: ESPN gzips responses even
 * when not asked (and {@link HttpClient} never decodes), and NHL {@code /now} URLs redirect.
 */
public final class UpstreamHttp {

    /** Largest body we'll read (NCAAB D-I scoreboards are ~2 MB decoded). */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final HttpClient client;
    private final ObjectMapper json;
    private final String userAgent;
    private final Duration requestTimeout;

    public UpstreamHttp(
            ObjectMapper json, String userAgent, Duration connectTimeout, Duration requestTimeout) {
        this.json = json;
        this.userAgent = userAgent;
        this.requestTimeout = requestTimeout;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
    }

    public static UpstreamHttp withDefaults(ObjectMapper json, String userAgent) {
        return new UpstreamHttp(json, userAgent, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    /** GET {@code uri} and parse the body as JSON. */
    public JsonNode getJson(String provider, URI uri) throws ProviderException {
        long start = System.nanoTime();
        try {
            JsonNode node = fetch(provider, uri);
            SportsMetrics.upstream(
                    provider, "ok", java.time.Duration.ofNanos(System.nanoTime() - start));
            return node;
        } catch (ProviderException e) {
            String outcome =
                    switch (e.kind()) {
                        case HTTP_STATUS -> e.status() >= 500 ? "http_5xx" : "http_4xx";
                        default -> e.kind().name().toLowerCase(java.util.Locale.ROOT);
                    };
            SportsMetrics.upstream(
                    provider, outcome, java.time.Duration.ofNanos(System.nanoTime() - start));
            throw e;
        }
    }

    private JsonNode fetch(String provider, URI uri) throws ProviderException {
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(requestTimeout)
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .header("Accept-Encoding", "gzip")
                        .GET()
                        .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(
                    provider, ProviderException.Kind.TIMEOUT, "timeout " + path(uri), e);
        } catch (IOException e) {
            throw new ProviderException(
                    provider,
                    ProviderException.Kind.NETWORK,
                    e.getClass().getSimpleName() + " " + path(uri),
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    provider, ProviderException.Kind.NETWORK, "interrupted " + path(uri), e);
        }

        byte[] body;
        try (InputStream in = response.body()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (HttpTimeoutException e) {
            throw new ProviderException(
                    provider, ProviderException.Kind.TIMEOUT, "timeout reading " + path(uri), e);
        } catch (IOException e) {
            throw new ProviderException(
                    provider, ProviderException.Kind.NETWORK, "read failed " + path(uri), e);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new ProviderException(
                    provider, ProviderException.Kind.PARSE, "body too large " + path(uri), null);
        }

        int status = response.statusCode();
        if (status < 200 || status > 299) {
            throw new ProviderException(
                    provider,
                    ProviderException.Kind.HTTP_STATUS,
                    status,
                    "HTTP " + status + " " + path(uri),
                    null);
        }

        try (InputStream in = decoded(body, response)) {
            JsonNode node = json.readTree(in);
            if (node == null || node.isMissingNode()) {
                throw new ProviderException(
                        provider, ProviderException.Kind.PARSE, "empty body " + path(uri), null);
            }
            return node;
        } catch (JacksonException e) {
            throw new ProviderException(
                    provider, ProviderException.Kind.PARSE, "invalid JSON " + path(uri), e);
        } catch (IOException e) {
            // Truncated gzip stream and the like.
            throw new ProviderException(
                    provider, ProviderException.Kind.PARSE, "unreadable body " + path(uri), e);
        }
    }

    private static InputStream decoded(byte[] body, HttpResponse<?> response) throws IOException {
        boolean gzipHeader =
                response.headers()
                        .firstValue("Content-Encoding")
                        .map(v -> v.equalsIgnoreCase("gzip"))
                        .orElse(false);
        // Trust the magic bytes too: some CDNs gzip without saying so.
        boolean gzipMagic =
                body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
        InputStream raw = new ByteArrayInputStream(body);
        return gzipHeader || gzipMagic ? new GZIPInputStream(raw) : raw;
    }

    /** Path + query only, for messages and logs (no host noise, never credentials). */
    private static String path(URI uri) {
        return uri.getRawQuery() == null
                ? uri.getRawPath()
                : uri.getRawPath() + "?" + uri.getRawQuery();
    }
}
