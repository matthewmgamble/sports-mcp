package ca.hex42labs.sports.application;

import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.persistence.GameRepository;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.Providers;
import ca.hex42labs.sports.provider.ScheduleResult;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps scores fresh on demand without multiplying upstream traffic.
 *
 * <ol>
 *   <li>Read the requested games from the database.
 *   <li>Work out which are older than {@link FreshnessPolicy} allows.
 *   <li>Group those by refresh key: one scoreboard per league per US-Eastern day refreshes every
 *       game on it. MLB live games also get their per-game linescore.
 *   <li>Fetch each key once: concurrent callers share the in-flight request, and a finished fetch
 *       is reused for {@code floor} so even {@code refresh=true} can't hammer upstream.
 *   <li>Upsert, re-read and return, marking anything still too old as stale with the reason.
 * </ol>
 */
public final class ScoreRefreshCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScoreRefreshCoordinator.class);

    /**
     * Upstream date buckets. ESPN's {@code dates=}, NHL's {@code score/{date}} and MLB's {@code
     * date=} are all US-Eastern slates (no game in these leagues starts after midnight Eastern), so
     * a game's bucket is the Eastern date of its start. A game running past midnight stays in
     * yesterday's bucket, which is exactly where upstream keeps it.
     */
    public static final ZoneId BUCKET_ZONE = ZoneId.of("America/New_York");

    /** How far back an on-request bucket fetch may reach (older days are settled). */
    static final int ON_DEMAND_PAST_DAYS = 7;

    /** "Live" candidates: started within this long, or LIVE in the DB within a day. */
    static final Duration LIVE_LOOKBACK = Duration.ofHours(8);

    static final Duration LIVE_HORIZON = Duration.ofMinutes(15);
    static final Duration ZOMBIE_LIVE = Duration.ofHours(24);

    /**
     * @param floor minimum interval between upstream fetches of one key, even with refresh=true
     * @param waitForUpstream how long a request waits for refreshes before serving what it has
     * @param horizonDays furthest future bucket fetched on request (matches the sync horizon)
     */
    public record Settings(Duration floor, Duration waitForUpstream, int horizonDays) {
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(5), Duration.ofSeconds(6), 14);
        }
    }

    /** One upstream fetch: a league-day scoreboard, or one game's per-game feed. */
    record RefreshKey(String league, LocalDate bucket, String gameId) {
        static RefreshKey bucket(String league, LocalDate bucket) {
            return new RefreshKey(league, bucket, null);
        }

        static RefreshKey game(Game g) {
            return new RefreshKey(g.league(), null, g.id());
        }

        @Override
        public String toString() {
            return gameId != null ? gameId : league + "@" + bucket;
        }
    }

    /** Outcome of a fetch. Failures are values, so they're shared and cached like successes. */
    sealed interface Fetch {
        record Ok(Instant at) implements Fetch {}

        record Failed(Instant at, String reason) implements Fetch {}
    }

    private final LeagueRegistry leagues;
    private final Providers providers;
    private final GameRepository games;
    private final TeamCatalog teams;
    private final FreshnessPolicy policy;
    private final Clock clock;
    private final Settings settings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AsyncCache<RefreshKey, Fetch> flights;

    /** When each league-day was last fetched, for the empty-bucket rule. */
    private final Cache<RefreshKey, Instant> bucketFetchedAt;

    /** Season schedules per team: kept 6 hours on success, retried after 2 minutes on failure. */
    private final AsyncCache<String, Fetch> teamSchedules;

    static final Duration TEAM_SCHEDULE_TTL = Duration.ofHours(6);
    static final Duration TEAM_SCHEDULE_RETRY = Duration.ofMinutes(2);

    public ScoreRefreshCoordinator(
            LeagueRegistry leagues,
            Providers providers,
            GameRepository games,
            TeamCatalog teams,
            FreshnessPolicy policy,
            Clock clock,
            Settings settings) {
        this.leagues = leagues;
        this.providers = providers;
        this.games = games;
        this.teams = teams;
        this.policy = policy;
        this.clock = clock;
        this.settings = settings;
        this.flights =
                Caffeine.newBuilder()
                        .expireAfterWrite(settings.floor())
                        .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                        .executor(executor)
                        .buildAsync();
        this.bucketFetchedAt =
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofHours(1))
                        .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                        .maximumSize(10_000)
                        .build();
        this.teamSchedules =
                Caffeine.newBuilder()
                        .expireAfter(
                                Expiry.<String, Fetch>creating(
                                        (k, f) ->
                                                f instanceof Fetch.Ok
                                                        ? TEAM_SCHEDULE_TTL
                                                        : TEAM_SCHEDULE_RETRY))
                        .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                        .maximumSize(5_000)
                        .executor(executor)
                        .buildAsync();
    }

    /** The bucket a game lives in upstream. */
    public static LocalDate bucketOf(Game g) {
        return g.startsAt().atZone(BUCKET_ZONE).toLocalDate();
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(BUCKET_ZONE));
    }

    // ---------------------------------------------------------------- public API

    /** Refreshes {@code requested} as needed and returns their current state, in the same order. */
    public Refreshed<List<Game>> ensureFresh(List<Game> requested, boolean force) {
        return refresh(requested, Set.of(), force);
    }

    public Refreshed<Optional<Game>> game(String id, boolean force) {
        Optional<Game> g = games.findById(id);
        if (g.isEmpty()) {
            return new Refreshed<>(Optional.empty(), clock.instant(), false, null, Set.of());
        }
        Refreshed<List<Game>> r = ensureFresh(List.of(g.get()), force);
        return new Refreshed<>(
                r.data().stream().findFirst(), r.asOf(), r.stale(), r.staleReason(), r.sources());
    }

    /**
     * Games in one league-day (US-Eastern bucket). If we hold none for that day, the bucket itself
     * is fetched (at most every {@code emptyBucket}), so a cold or unsynced day fills in.
     */
    public Refreshed<List<Game>> day(League league, LocalDate bucket, boolean force) {
        Instant from = bucket.atStartOfDay(BUCKET_ZONE).toInstant();
        Instant to = bucket.plusDays(1).atStartOfDay(BUCKET_ZONE).toInstant();
        List<Game> stored = games.findByLeague(league.id(), from, to, 1_000);
        Set<RefreshKey> extra =
                stored.isEmpty() ? emptyBucketKeys(league, bucket, force) : Set.of();
        Refreshed<List<Game>> r = refresh(stored, extra, force);
        if (extra.isEmpty()) {
            return r;
        }
        // The bucket fetch may have found games we didn't have.
        List<Game> after = games.findByLeague(league.id(), from, to, 1_000);
        return new Refreshed<>(
                after, r.asOf(), r.stale(), r.staleReason(), sources(after, r.sources()));
    }

    /**
     * Games in progress now, refreshed. Candidates are games the database thinks are live, plus
     * scheduled games that should have started (status lags) or start within minutes.
     *
     * @param leagueId one league, or null for all enabled leagues
     * @param teamId only this team's games, or null
     */
    public Refreshed<List<Game>> live(String leagueId, String teamId, boolean force) {
        Instant now = clock.instant();
        List<League> scope =
                leagueId == null ? leagues.enabled() : leagues.find(leagueId).stream().toList();
        List<Game> candidates = new ArrayList<>();
        Set<RefreshKey> extra = new LinkedHashSet<>();
        for (League league : scope) {
            List<Game> active =
                    games
                            .findActive(
                                    league.id(),
                                    now.minus(LIVE_LOOKBACK),
                                    now.plus(LIVE_HORIZON),
                                    now.minus(ZOMBIE_LIVE))
                            .stream()
                            .filter(
                                    g ->
                                            teamId == null
                                                    || teamId.equals(g.homeTeamId())
                                                    || teamId.equals(g.awayTeamId()))
                            .toList();
            candidates.addAll(active);
            if (active.isEmpty() && teamId == null && !hasGamesOn(league, today())) {
                extra.addAll(emptyBucketKeys(league, today(), force));
            }
        }
        Refreshed<List<Game>> r = refresh(candidates, extra, force);
        List<Game> liveNow = r.data().stream().filter(g -> g.status() == GameStatus.LIVE).toList();
        if (!extra.isEmpty()) {
            // A bucket fetch for an empty day may have surfaced live games.
            List<Game> more = new ArrayList<>(liveNow);
            for (RefreshKey k : extra) {
                Instant from = k.bucket().atStartOfDay(BUCKET_ZONE).toInstant();
                games.findByLeague(k.league(), from, from.plus(Duration.ofDays(1)), 1_000).stream()
                        .filter(g -> g.status() == GameStatus.LIVE)
                        .filter(g -> more.stream().noneMatch(m -> m.id().equals(g.id())))
                        .forEach(more::add);
            }
            liveNow = more;
        }
        return new Refreshed<>(
                liveNow, r.asOf(), r.stale(), r.staleReason(), sources(liveNow, Set.of()));
    }

    /**
     * Makes sure {@code team}'s season schedule has been pulled recently (it reaches beyond the
     * background sync horizon). Concurrent callers share one fetch.
     *
     * @return a failure reason if the schedule couldn't be refreshed
     */
    public Optional<String> ensureTeamSchedule(Team team, boolean force) {
        if (force) {
            Fetch cached = teamSchedules.synchronous().getIfPresent(team.id());
            if (cached != null
                    && Duration.between(fetchedAt(cached), clock.instant())
                                    .compareTo(Duration.ofMinutes(1))
                            > 0) {
                teamSchedules.synchronous().invalidate(team.id());
            }
        }
        CompletableFuture<Fetch> f =
                teamSchedules.get(
                        team.id(),
                        (k, exec) ->
                                CompletableFuture.supplyAsync(() -> loadTeamSchedule(team), exec));
        try {
            Fetch result = f.get(settings.waitForUpstream().toNanos(), TimeUnit.NANOSECONDS);
            return result instanceof Fetch.Failed failed
                    ? Optional.of(failed.reason())
                    : Optional.empty();
        } catch (TimeoutException e) {
            return Optional.of(team.id() + " schedule: still waiting on upstream");
        } catch (ExecutionException e) {
            return Optional.of(team.id() + " schedule: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        }
    }

    private Fetch loadTeamSchedule(Team team) {
        Optional<League> league = leagues.find(team.league());
        if (league.isEmpty()) {
            return new Fetch.Failed(clock.instant(), "league " + team.league() + " disabled");
        }
        try {
            ScheduleResult r =
                    providers.forLeague(league.get()).fetchTeamSchedule(league.get(), team);
            teams.addFromGames(r.teams());
            games.upsertAll(r.games(), clock.instant());
            log.debug("Team schedule {}: {} games", team.id(), r.games().size());
            return new Fetch.Ok(clock.instant());
        } catch (ProviderException e) {
            log.info("Team schedule {} failed: {}", team.id(), e.getMessage());
            return new Fetch.Failed(clock.instant(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Team schedule {} failed unexpectedly", team.id(), e);
            return new Fetch.Failed(
                    clock.instant(), team.id() + ": " + e.getClass().getSimpleName());
        }
    }

    private static Instant fetchedAt(Fetch f) {
        return switch (f) {
            case Fetch.Ok ok -> ok.at();
            case Fetch.Failed failed -> failed.at();
        };
    }

    // ---------------------------------------------------------------- core

    private Refreshed<List<Game>> refresh(
            List<Game> requested, Set<RefreshKey> extraBuckets, boolean force) {
        Instant now = clock.instant();
        Map<RefreshKey, List<Game>> due = new LinkedHashMap<>();
        for (Game g : requested) {
            if (needsRefresh(g, now, force) && inOnDemandRange(bucketOf(g))) {
                due.computeIfAbsent(
                                RefreshKey.bucket(g.league(), bucketOf(g)), k -> new ArrayList<>())
                        .add(g);
            }
        }
        extraBuckets.forEach(k -> due.putIfAbsent(k, new ArrayList<>()));
        if (due.isEmpty()) {
            return result(requested, now, List.of());
        }

        List<String> failures = new ArrayList<>();
        Map<RefreshKey, CompletableFuture<Fetch>> inFlight = new LinkedHashMap<>();
        due.keySet().forEach(k -> inFlight.put(k, flight(k)));
        awaitAll(inFlight, failures);

        // Second stage: per-game feeds for live games whose provider has a fresher one (MLB).
        Map<RefreshKey, CompletableFuture<Fetch>> perGame = new LinkedHashMap<>();
        Map<String, Game> current = byId(games.findByIds(ids(requested)));
        for (Map.Entry<RefreshKey, List<Game>> e : due.entrySet()) {
            for (Game g : e.getValue()) {
                Game latest = current.get(g.id());
                if (latest != null
                        && latest.status() == GameStatus.LIVE
                        && supportsPerGame(latest)) {
                    perGame.put(RefreshKey.game(latest), flight(RefreshKey.game(latest)));
                }
            }
        }
        awaitAll(perGame, failures);

        List<Game> after = new ArrayList<>();
        Map<String, Game> fresh = byId(games.findByIds(ids(requested)));
        for (Game g : requested) {
            after.add(fresh.getOrDefault(g.id(), g));
        }
        return result(after, clock.instant(), failures);
    }

    private boolean needsRefresh(Game g, Instant now, boolean force) {
        Duration age = Duration.between(g.fetchedAt(), now);
        Duration limit = force ? settings.floor() : policy.maxAge(g, now);
        return age.compareTo(limit) > 0;
    }

    private boolean inOnDemandRange(LocalDate bucket) {
        LocalDate today = today();
        return !bucket.isBefore(today.minusDays(ON_DEMAND_PAST_DAYS))
                && !bucket.isAfter(today.plusDays(settings.horizonDays()));
    }

    private Set<RefreshKey> emptyBucketKeys(League league, LocalDate bucket, boolean force) {
        if (!inOnDemandRange(bucket)) {
            return Set.of();
        }
        RefreshKey key = RefreshKey.bucket(league.id(), bucket);
        Instant last = bucketFetchedAt.getIfPresent(key);
        Duration limit = force ? settings.floor() : policy.settings().emptyBucket();
        boolean due = last == null || Duration.between(last, clock.instant()).compareTo(limit) > 0;
        return due ? Set.of(key) : Set.of();
    }

    private boolean hasGamesOn(League league, LocalDate bucket) {
        Instant from = bucket.atStartOfDay(BUCKET_ZONE).toInstant();
        return !games.findByLeague(league.id(), from, from.plus(Duration.ofDays(1)), 1).isEmpty();
    }

    private boolean supportsPerGame(Game g) {
        return leagues.find(g.league())
                .map(l -> providers.forLeague(l).hasPerGameFeed())
                .orElse(false);
    }

    /** Joins the shared in-flight fetch for {@code key}, starting one if there isn't one. */
    private CompletableFuture<Fetch> flight(RefreshKey key) {
        return flights.get(key, (k, exec) -> CompletableFuture.supplyAsync(() -> load(k), exec));
    }

    private Fetch load(RefreshKey key) {
        Optional<League> league = leagues.find(key.league());
        if (league.isEmpty()) {
            return new Fetch.Failed(clock.instant(), "league " + key.league() + " disabled");
        }
        try {
            if (key.gameId() != null) {
                Optional<Game> g = games.findById(key.gameId());
                if (g.isPresent()) {
                    Optional<Game> updated =
                            providers.forLeague(league.get()).refreshGame(league.get(), g.get());
                    updated.ifPresent(u -> games.upsertAll(List.of(u), clock.instant()));
                }
                return new Fetch.Ok(clock.instant());
            }
            ProviderBatch batch =
                    providers.forLeague(league.get()).fetchScoreboard(league.get(), key.bucket());
            teams.addFromGames(batch.teams());
            games.upsertAll(batch.games(), clock.instant());
            bucketFetchedAt.put(key, batch.fetchedAt());
            log.debug("Refreshed {}: {} games", key, batch.games().size());
            return new Fetch.Ok(batch.fetchedAt());
        } catch (ProviderException e) {
            log.info("Refresh {} failed: {}", key, e.getMessage());
            if (key.gameId() == null) {
                bucketFetchedAt.put(
                        key, clock.instant()); // don't retry an empty-day probe every request
            }
            return new Fetch.Failed(clock.instant(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Refresh {} failed unexpectedly", key, e);
            return new Fetch.Failed(clock.instant(), key + ": " + e.getClass().getSimpleName());
        }
    }

    private void awaitAll(
            Map<RefreshKey, CompletableFuture<Fetch>> inFlight, List<String> failures) {
        if (inFlight.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + settings.waitForUpstream().toNanos();
        for (Map.Entry<RefreshKey, CompletableFuture<Fetch>> e : inFlight.entrySet()) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (e.getValue().get(remaining, TimeUnit.NANOSECONDS) instanceof Fetch.Failed f) {
                    failures.add(f.reason());
                }
            } catch (TimeoutException ex) {
                // The fetch carries on and lands in the DB for the next request; serve what we
                // have.
                failures.add(e.getKey() + ": still waiting on upstream");
            } catch (ExecutionException ex) {
                failures.add(e.getKey() + ": " + ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failures.add(e.getKey() + ": interrupted");
                return;
            }
        }
    }

    private Refreshed<List<Game>> result(List<Game> data, Instant now, List<String> failures) {
        Instant asOf =
                data.stream().map(Game::fetchedAt).min(Comparator.naturalOrder()).orElse(now);
        boolean stale =
                data.stream().anyMatch(g -> policy.isStale(g, now) && inOnDemandRange(bucketOf(g)));
        String reason = null;
        if (stale || !failures.isEmpty()) {
            reason =
                    failures.isEmpty()
                            ? "refresh pending"
                            : String.join("; ", new TreeSet<>(failures));
        }
        return new Refreshed<>(data, asOf, stale, stale ? reason : null, sources(data, Set.of()));
    }

    private static Set<String> sources(List<Game> data, Set<String> extra) {
        Set<String> s = new TreeSet<>(extra);
        data.forEach(g -> s.add(g.provider()));
        return s;
    }

    private static List<String> ids(Collection<Game> gs) {
        return gs.stream().map(Game::id).distinct().toList();
    }

    private static Map<String, Game> byId(Collection<Game> gs) {
        Map<String, Game> m = new LinkedHashMap<>();
        gs.forEach(g -> m.put(g.id(), g));
        return m;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
