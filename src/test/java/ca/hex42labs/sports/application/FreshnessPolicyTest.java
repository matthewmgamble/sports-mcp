package ca.hex42labs.sports.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.testing.Games;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-12T23:00:00Z");
    private final FreshnessPolicy policy = new FreshnessPolicy(FreshnessPolicy.Settings.defaults());

    private static Game game(GameStatus status, Duration startOffset) {
        Game g = Games.scheduled("nhl:1", "nhl:tor", "nhl:mtl", NOW.plus(startOffset), NOW);
        return Games.rescheduled(g, g.startsAt(), status, NOW);
    }

    @ParameterizedTest(name = "{0} starting {1} -> {2}")
    @CsvSource({
        "LIVE,       -PT1H,   PT15S",
        "SCHEDULED,  PT59M,   PT60S",
        "SCHEDULED,  PT1H,    PT60S",
        "SCHEDULED,  PT1H1M,  PT30M",
        "SCHEDULED,  PT47H,   PT30M",
        "SCHEDULED,  PT49H,   PT6H",
        "SCHEDULED,  PT0S,    PT30S",
        "SCHEDULED,  -PT10M,  PT30S",
        "UNKNOWN,    PT10M,   PT60S",
        "FINAL,      -PT3H,   PT5M",
        "FINAL,      -PT7H,   PT87600H",
        "SUSPENDED,  -PT2H,   PT5M",
        "POSTPONED,  PT2H,    PT6H",
        "CANCELLED,  PT2H,    PT6H",
    })
    void maxAgeByStatusAndTimeToStart(GameStatus status, Duration startOffset, Duration expected) {
        assertThat(policy.maxAge(game(status, startOffset), NOW)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "live fetched {0}s ago stale={1}")
    @CsvSource({"14, false", "15, false", "16, true"})
    void liveBoundaryIsInclusive(int ageSeconds, boolean stale) {
        Game g =
                Games.live(
                        game(GameStatus.LIVE, Duration.ofHours(-1)),
                        1,
                        0,
                        "10:00",
                        NOW.minusSeconds(ageSeconds));
        assertThat(policy.isStale(g, NOW)).isEqualTo(stale);
    }
}
