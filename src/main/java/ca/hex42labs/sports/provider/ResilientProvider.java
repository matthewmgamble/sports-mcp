package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a provider with a rate limiter (protects upstream from us) and a circuit breaker (stops
 * hammering an upstream that's down, so callers fail fast and serve stale data). Every upstream
 * call, background or on-demand, goes through here.
 */
public final class ResilientProvider implements SportsProvider {

    /**
     * @param permitsPerSecond sustained upstream calls per second
     * @param permitWait how long a call may queue for a permit before failing RATE_LIMITED
     * @param openFor how long the breaker stays open before letting trial calls through
     */
    public record Limits(int permitsPerSecond, Duration permitWait, Duration openFor) {
        public static Limits forProvider(String id) {
            return switch (id) {
                // statsapi trips its edge limit on rapid bursts (Pulseboard saw 406s).
                case "mlb" -> new Limits(2, Duration.ofSeconds(3), Duration.ofSeconds(30));
                case "nhl" -> new Limits(3, Duration.ofSeconds(3), Duration.ofSeconds(30));
                default -> new Limits(5, Duration.ofSeconds(3), Duration.ofSeconds(30));
            };
        }
    }

    private final SportsProvider delegate;
    private final RateLimiter limiter;
    private final CircuitBreaker breaker;

    public ResilientProvider(SportsProvider delegate, Limits limits) {
        this.delegate = delegate;
        this.limiter =
                RateLimiter.of(
                        delegate.id(),
                        RateLimiterConfig.custom()
                                .limitForPeriod(limits.permitsPerSecond())
                                .limitRefreshPeriod(Duration.ofSeconds(1))
                                .timeoutDuration(limits.permitWait())
                                .build());
        this.breaker =
                CircuitBreaker.of(
                        delegate.id(),
                        CircuitBreakerConfig.custom()
                                .slidingWindowType(
                                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                                .slidingWindowSize(20)
                                .minimumNumberOfCalls(10)
                                .failureRateThreshold(50)
                                .waitDurationInOpenState(limits.openFor())
                                .permittedNumberOfCallsInHalfOpenState(3)
                                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                                .build());
    }

    public static ResilientProvider wrap(SportsProvider delegate) {
        return new ResilientProvider(delegate, Limits.forProvider(delegate.id()));
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public List<Team> fetchTeams(League league) throws ProviderException {
        return call(() -> delegate.fetchTeams(league));
    }

    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException {
        return call(() -> delegate.fetchScoreboard(league, bucket));
    }

    /**
     * A schedule fetch is several upstream calls; it's admitted as one, and individual failures
     * inside it are reported in the result rather than tripping the breaker.
     */
    @Override
    public ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
            throws ProviderException {
        return call(() -> delegate.fetchSchedule(league, from, to));
    }

    @Override
    public ScheduleResult fetchTeamSchedule(League league, Team team) throws ProviderException {
        return call(() -> delegate.fetchTeamSchedule(league, team));
    }

    @Override
    public Optional<Game> refreshGame(League league, Game game) throws ProviderException {
        return call(() -> delegate.refreshGame(league, game));
    }

    @Override
    public boolean hasPerGameFeed() {
        return delegate.hasPerGameFeed();
    }

    private interface Call<T> {
        T run() throws ProviderException;
    }

    private <T> T call(Call<T> call) throws ProviderException {
        if (!breaker.tryAcquirePermission()) {
            throw new ProviderException(
                    id(),
                    ProviderException.Kind.CIRCUIT_OPEN,
                    "temporarily unavailable (circuit open)",
                    null);
        }
        if (!limiter.acquirePermission()) {
            breaker.releasePermission();
            throw new ProviderException(
                    id(), ProviderException.Kind.RATE_LIMITED, "rate limited", null);
        }
        long start = System.nanoTime();
        try {
            T result = call.run();
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (ProviderException e) {
            if (isUpstreamFault(e)) {
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            } else {
                breaker.releasePermission();
            }
            throw e;
        } catch (RuntimeException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw e;
        }
    }

    /**
     * Timeouts, network errors, garbage bodies, 5xx and 429 mean upstream trouble. Other 4xx (e.g.
     * ESPN's 400 for date ranges) mean our request was wrong; they don't trip the breaker.
     */
    static boolean isUpstreamFault(ProviderException e) {
        return switch (e.kind()) {
            case TIMEOUT, NETWORK, PARSE -> true;
            case HTTP_STATUS -> e.status() >= 500 || e.status() == 429;
            case RATE_LIMITED, CIRCUIT_OPEN -> false;
        };
    }
}
