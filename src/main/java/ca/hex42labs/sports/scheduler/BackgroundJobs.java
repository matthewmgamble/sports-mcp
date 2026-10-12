package ca.hex42labs.sports.scheduler;

import ca.hex42labs.sports.application.ScheduleSync;
import ca.hex42labs.sports.application.SyncPlanner;
import ca.hex42labs.sports.application.TeamCatalog;
import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.persistence.GameRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives background work from a one-minute tick. Each tick asks {@link SyncPlanner} what's due
 * based on persisted sync state, so a restart, a missed run or an outage simply catches up on the
 * next tick instead of relying on timers that reset.
 */
public final class BackgroundJobs implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackgroundJobs.class);

    static final Duration TICK = Duration.ofMinutes(1);
    static final Duration TEAM_MAX_AGE = Duration.ofHours(24);
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);
    static final Duration RETENTION_EVERY = Duration.ofHours(24);

    private final LeagueRegistry leagues;
    private final TeamCatalog teams;
    private final ScheduleSync schedules;
    private final SyncPlanner planner;
    private final GameRepository games;
    private final Clock clock;
    private final Duration retention;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("background-jobs").daemon().factory());
    private Instant lastRetention;

    public BackgroundJobs(
            LeagueRegistry leagues,
            TeamCatalog teams,
            ScheduleSync schedules,
            SyncPlanner planner,
            GameRepository games,
            Clock clock,
            Duration retention) {
        this.leagues = leagues;
        this.teams = teams;
        this.schedules = schedules;
        this.planner = planner;
        this.games = games;
        this.clock = clock;
        this.retention = retention;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::safeTick, 0, TICK.toSeconds(), TimeUnit.SECONDS);
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            // Never let one bad tick cancel the schedule.
            log.error("Background tick failed", e);
        }
    }

    /**
     * One pass: teams, then schedules league by league, then retention. Package-visible for tests.
     */
    void tick() {
        teams.syncStale(TEAM_MAX_AGE, RETRY_AFTER);
        for (League league : leagues.enabled()) {
            switch (planner.due(league)) {
                case FULL -> schedules.full(league, false);
                case NEAR_TERM -> schedules.nearTerm(league, false);
                case NONE -> {}
            }
        }
        Instant now = clock.instant();
        if (lastRetention == null || !lastRetention.isAfter(now.minus(RETENTION_EVERY))) {
            int deleted = games.deleteStartedBefore(now.minus(retention));
            if (deleted > 0) {
                log.info(
                        "Retention removed {} games older than {} days",
                        deleted,
                        retention.toDays());
            }
            lastRetention = now;
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
