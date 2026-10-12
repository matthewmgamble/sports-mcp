package ca.hex42labs.sports.application;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.SyncState;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.ScheduleResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pulls a league's schedule for a date window and persists it, so upcoming games are available
 * before anyone asks. Used by the background scheduler and the admin resync endpoint.
 */
public final class ScheduleSync {

    private static final Logger log = LoggerFactory.getLogger(ScheduleSync.class);

    /** Look-behind: yesterday's slate still matters (late games, overnight finals). */
    static final int LOOK_BEHIND_DAYS = 1;

    /** Near-term window: the next 48 hours. */
    static final int NEAR_TERM_DAYS = 2;

    private final Providers providers;
    private final GameRepository games;
    private final TeamCatalog teams;
    private final SyncStateRepository syncState;
    private final Clock clock;
    private final int horizonDays;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ScheduleSync(
            Providers providers,
            GameRepository games,
            TeamCatalog teams,
            SyncStateRepository syncState,
            Clock clock,
            int horizonDays) {
        this.providers = providers;
        this.games = games;
        this.teams = teams;
        this.syncState = syncState;
        this.clock = clock;
        this.horizonDays = horizonDays;
    }

    /**
     * @param skipped another sync for this league was already running
     * @param failures per-request failures (the rest of the window was still saved)
     */
    public record Outcome(
            String league,
            SyncState.Kind kind,
            LocalDate from,
            LocalDate to,
            int games,
            int changed,
            List<String> failures,
            boolean skipped) {

        public boolean ok() {
            return !skipped && failures.isEmpty();
        }
    }

    /** Today in the league's own timezone. */
    public LocalDate today(League league) {
        return LocalDate.now(clock.withZone(league.displayTimezone()));
    }

    public Outcome full(League league, boolean wait) {
        LocalDate today = today(league);
        return sync(
                league,
                SyncState.Kind.SCHEDULE,
                today.minusDays(LOOK_BEHIND_DAYS),
                today.plusDays(horizonDays),
                wait);
    }

    public Outcome nearTerm(League league, boolean wait) {
        LocalDate today = today(league);
        return sync(
                league,
                SyncState.Kind.SCHEDULE_NEAR,
                today.minusDays(LOOK_BEHIND_DAYS),
                today.plusDays(NEAR_TERM_DAYS),
                wait);
    }

    /**
     * Syncs {@code from..to} inclusive. Only one sync per league runs at a time: with {@code wait}
     * false a concurrent request is skipped, otherwise it queues.
     */
    public Outcome sync(
            League league, SyncState.Kind kind, LocalDate from, LocalDate to, boolean wait) {
        ReentrantLock lock = locks.computeIfAbsent(league.id(), k -> new ReentrantLock());
        if (wait) {
            lock.lock();
        } else if (!lock.tryLock()) {
            return new Outcome(league.id(), kind, from, to, 0, 0, List.of(), true);
        }
        try {
            return doSync(league, kind, from, to);
        } finally {
            lock.unlock();
        }
    }

    private Outcome doSync(League league, SyncState.Kind kind, LocalDate from, LocalDate to) {
        Instant started = clock.instant();
        syncState.recordAttempt(league.id(), kind, started);
        ScheduleResult result;
        try {
            result = providers.forLeague(league).fetchSchedule(league, from, to);
        } catch (ProviderException | RuntimeException e) {
            syncState.recordFailure(league.id(), kind, clock.instant(), e.getMessage());
            log.warn("{} sync {} {}..{} failed: {}", kind, league.id(), from, to, e.getMessage());
            return new Outcome(
                    league.id(),
                    kind,
                    from,
                    to,
                    0,
                    0,
                    List.of(String.valueOf(e.getMessage())),
                    false);
        }
        // Teams first so every saved game's teams exist in the catalog.
        teams.addFromGames(result.teams());
        int changed = games.upsertAll(result.games(), clock.instant());
        if (result.complete()) {
            // An empty window (off-season) is a successful sync, not an error.
            syncState.recordSuccess(league.id(), kind, clock.instant(), from, to);
        } else {
            syncState.recordFailure(
                    league.id(), kind, clock.instant(), String.join("; ", result.failures()));
        }
        log.info(
                "{} sync {} {}..{}: {} games ({} changed){} in {} ms",
                kind,
                league.id(),
                from,
                to,
                result.games().size(),
                changed,
                result.complete() ? "" : ", " + result.failures().size() + " failed requests",
                Duration.between(started, clock.instant()).toMillis());
        return new Outcome(
                league.id(),
                kind,
                from,
                to,
                result.games().size(),
                changed,
                result.failures(),
                false);
    }

    /** Last persisted state, for due checks. */
    Optional<SyncState> state(League league, SyncState.Kind kind) {
        return syncState.find(league.id(), kind);
    }
}
