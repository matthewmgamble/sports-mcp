package ca.hex42labs.sports.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClientIdentityTest {

    private final ClientIdentity id =
            new ClientIdentity(
                    List.of("173.245.48.0/20", "2400:cb00::/32", "10.0.0.0/8"),
                    List.of(ClientIdentity.sha256("good-key")));

    @Test
    void untrustedPeersCannotSpoofForwardingHeaders() {
        assertThat(id.clientIp("203.0.113.9", "1.2.3.4", "5.6.7.8")).isEqualTo("203.0.113.9");
    }

    @Test
    void trustedProxyForwardsCloudflareClientIp() {
        assertThat(id.clientIp("173.245.50.1", "198.51.100.7", null)).isEqualTo("198.51.100.7");
        assertThat(id.clientIp("2400:cb00:1::1", "2001:db8::42", null)).isEqualTo("2001:db8::42");
    }

    @Test
    void forwardedForTakesRightmostUntrustedHop() {
        // client spoofs a leading entry; our proxies append the real hops
        assertThat(id.clientIp("10.0.0.5", null, "6.6.6.6, 198.51.100.7, 10.0.0.9"))
                .isEqualTo("198.51.100.7");
    }

    @Test
    void cidrMatching() {
        Cidr c = Cidr.parse("173.245.48.0/20");
        assertThat(c.contains("173.245.63.255")).isTrue();
        assertThat(c.contains("173.245.64.0")).isFalse();
        assertThat(c.contains("not-an-ip.example")).isFalse(); // never resolves DNS
        assertThat(Cidr.parse("2400:cb00::/32").contains("173.245.48.1")).isFalse();
        assertThat(Cidr.parse("192.0.2.1").contains("192.0.2.1")).isTrue();
    }

    @Test
    void apiKeyHashesAreComparedNotStored() {
        assertThat(ClientIdentity.sha256("good-key")).hasSize(64).doesNotContain("good-key");
    }

    @Test
    void ipHashIsStableWithinADayAndNotReversible() {
        var h = new PublicTrafficFilter.IpHasher();
        String a = h.hash("203.0.113.9");
        assertThat(a)
                .hasSize(12)
                .isEqualTo(h.hash("203.0.113.9"))
                .isNotEqualTo(h.hash("203.0.113.10"));
        assertThat(a).doesNotContain("203");
    }

    @Test
    void tokenBucketAllowsBurstThenRefuses() {
        TokenBucket b = new TokenBucket(60, 3);
        assertThat(b.tryTake()).isZero();
        assertThat(b.tryTake()).isZero();
        assertThat(b.tryTake()).isZero();
        assertThat(b.tryTake()).isPositive().isLessThanOrEqualTo(1_000_000_000L);
    }
}
