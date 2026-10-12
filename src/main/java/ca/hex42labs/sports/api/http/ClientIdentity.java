package ca.hex42labs.sports.api.http;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Who is calling, for rate limits. A valid API key wins; otherwise the client IP. Forwarding
 * headers ({@code CF-Connecting-IP}, {@code X-Forwarded-For}) are believed only when the socket
 * peer is a configured trusted proxy, so they can't be spoofed to dodge limits.
 */
public final class ClientIdentity {

    /**
     * @param key rate-limit bucket key ("ip:203.0.113.9" or "key:3f2a...")
     * @param ip the client IP (for log hashing)
     * @param keyed authenticated with an API key (higher limits)
     */
    public record Client(String key, String ip, boolean keyed) {}

    private final List<Cidr> trustedProxies;
    private final Set<String> apiKeyHashes;

    public ClientIdentity(List<String> trustedProxyCidrs, List<String> apiKeySha256) {
        this.trustedProxies = Cidr.parseAll(trustedProxyCidrs);
        this.apiKeyHashes =
                Set.copyOf(apiKeySha256.stream().map(s -> s.trim().toLowerCase()).toList());
    }

    public Client resolve(HttpServletRequest req) {
        String ip =
                clientIp(
                        req.getRemoteAddr(),
                        req.getHeader("CF-Connecting-IP"),
                        req.getHeader("X-Forwarded-For"));
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && !apiKeyHashes.isEmpty()) {
            String hash = sha256(header.substring(7).trim());
            if (apiKeyHashes.contains(hash)) {
                return new Client("key:" + hash.substring(0, 16), ip, true);
            }
        }
        return new Client("ip:" + ip, ip, false);
    }

    String clientIp(String peer, String cfConnectingIp, String forwardedFor) {
        if (trustedProxies.stream().noneMatch(c -> c.contains(peer))) {
            return peer;
        }
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp.trim();
        }
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Rightmost entry not added by one of our proxies is the client.
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (trustedProxies.stream().noneMatch(c -> c.contains(hop))) {
                    return hop;
                }
            }
        }
        return peer;
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
