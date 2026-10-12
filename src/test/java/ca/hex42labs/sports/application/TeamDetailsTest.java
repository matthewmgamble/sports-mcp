package ca.hex42labs.sports.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.testing.TestLeagues;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TeamDetailsTest {

    private static final Team LEAFS =
            new Team(
                    "nhl:tor",
                    "nhl",
                    "nhl",
                    "TOR",
                    "Toronto Maple Leafs",
                    "Maple Leafs",
                    "TOR",
                    "Toronto",
                    null,
                    List.of());

    @Test
    void detailsAreKeptForHalfAnHourAndServedStaleWhenUpstreamFails() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-12T14:00:00Z"));
        Clock clock =
                new Clock() {
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    public Instant instant() {
                        return now.get();
                    }
                };
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        TeamDetails details =
                new TeamDetails(
                        (league, team) -> {
                            calls.incrementAndGet();
                            if (fail.get()) {
                                throw new ProviderException(
                                        "espn", ProviderException.Kind.TIMEOUT, "timed out", null);
                            }
                            return new TeamDetails.Details(
                                    "003e7e", "3-2-1", "4th in Atlantic Division");
                        },
                        clock);

        TeamDetails.Result first = details.get(TestLeagues.get("nhl"), LEAFS);
        assertThat(first.details().record()).isEqualTo("3-2-1");
        assertThat(first.staleReason()).isNull();
        details.get(TestLeagues.get("nhl"), LEAFS);
        assertThat(calls.get()).isEqualTo(1);

        now.set(now.get().plus(TeamDetails.TTL).plus(Duration.ofSeconds(1)));
        fail.set(true);
        TeamDetails.Result stale = details.get(TestLeagues.get("nhl"), LEAFS);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(stale.details().standing()).isEqualTo("4th in Atlantic Division");
        assertThat(stale.staleReason()).contains("timed out");
    }
}
