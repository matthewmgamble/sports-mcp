package ca.hex42labs.sports.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.hex42labs.sports.testing.FakeProvider;
import ca.hex42labs.sports.testing.TestLeagues;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResilientProviderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);

    private static ProviderBatch empty() {
        return new ProviderBatch("nfl", DAY, List.of(), List.of(), Instant.EPOCH);
    }

    @Test
    void repeatedUpstreamFailuresOpenTheCircuitAndStopCalls() {
        FakeProvider fake =
                new FakeProvider("espn")
                        .scoreboard(
                                (l, d) -> {
                                    throw new ProviderException(
                                            "espn",
                                            ProviderException.Kind.TIMEOUT,
                                            "timeout",
                                            null);
                                });
        ResilientProvider p =
                new ResilientProvider(
                        fake,
                        new ResilientProvider.Limits(
                                100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> p.fetchScoreboard(TestLeagues.get("nfl"), DAY))
                    .isInstanceOf(ProviderException.class);
        }
        assertThat(p.breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int calls = fake.scoreboardCalls.size();
        assertThatThrownBy(() -> p.fetchScoreboard(TestLeagues.get("nfl"), DAY))
                .isInstanceOfSatisfying(
                        ProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.CIRCUIT_OPEN));
        assertThat(fake.scoreboardCalls).hasSize(calls); // upstream not touched
    }

    @Test
    void clientErrorsDoNotTripTheBreaker() {
        FakeProvider fake =
                new FakeProvider("espn")
                        .scoreboard(
                                (l, d) -> {
                                    throw new ProviderException(
                                            "espn",
                                            ProviderException.Kind.HTTP_STATUS,
                                            400,
                                            "HTTP 400",
                                            null);
                                });
        ResilientProvider p =
                new ResilientProvider(
                        fake,
                        new ResilientProvider.Limits(
                                100, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> p.fetchScoreboard(TestLeagues.get("nfl"), DAY))
                    .isInstanceOf(ProviderException.class);
        }
        assertThat(p.breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void rateLimiterRefusesBeyondItsBudget() throws Exception {
        FakeProvider fake = new FakeProvider("mlb").scoreboard((l, d) -> empty());
        ResilientProvider p =
                new ResilientProvider(
                        fake,
                        new ResilientProvider.Limits(2, Duration.ZERO, Duration.ofMinutes(1)));
        p.fetchScoreboard(TestLeagues.get("mlb"), DAY);
        p.fetchScoreboard(TestLeagues.get("mlb"), DAY);
        assertThatThrownBy(() -> p.fetchScoreboard(TestLeagues.get("mlb"), DAY))
                .isInstanceOfSatisfying(
                        ProviderException.class,
                        e -> assertThat(e.kind()).isEqualTo(ProviderException.Kind.RATE_LIMITED));
        assertThat(fake.scoreboardCalls).hasSize(2);
    }

    @Test
    void faultClassification() {
        assertThat(
                        ResilientProvider.isUpstreamFault(
                                new ProviderException(
                                        "x", ProviderException.Kind.HTTP_STATUS, 503, "", null)))
                .isTrue();
        assertThat(
                        ResilientProvider.isUpstreamFault(
                                new ProviderException(
                                        "x", ProviderException.Kind.HTTP_STATUS, 429, "", null)))
                .isTrue();
        assertThat(
                        ResilientProvider.isUpstreamFault(
                                new ProviderException(
                                        "x", ProviderException.Kind.HTTP_STATUS, 404, "", null)))
                .isFalse();
        assertThat(
                        ResilientProvider.isUpstreamFault(
                                new ProviderException("x", ProviderException.Kind.PARSE, "", null)))
                .isTrue();
        assertThat(
                        ResilientProvider.isUpstreamFault(
                                new ProviderException(
                                        "x", ProviderException.Kind.CIRCUIT_OPEN, "", null)))
                .isFalse();
    }
}
