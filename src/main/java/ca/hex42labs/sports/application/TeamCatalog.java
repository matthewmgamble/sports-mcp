package ca.hex42labs.sports.application;

import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.SyncState;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.persistence.SyncStateRepository;
import ca.hex42labs.sports.persistence.TeamRepository;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.Providers;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All known teams, persisted in SQLite and held in memory (about 1,300 rows across the leagues),
 * kept fresh by a daily per-league sync.
 */
public final class TeamCatalog {

    private static final Logger log = LoggerFactory.getLogger(TeamCatalog.class);

    private final LeagueRegistry leagues;
    private final Providers providers;
    private final TeamRepository repo;
    private final SyncStateRepository syncState;
    private final TeamAliases aliases;
    private final Clock clock;

    /** Copy-on-write snapshot; readers never lock. */
    private volatile Map<String, Team> byId = Map.of();

    public TeamCatalog(
            LeagueRegistry leagues,
            Providers providers,
            TeamRepository repo,
            SyncStateRepository syncState,
            TeamAliases aliases,
            Clock clock) {
        this.leagues = leagues;
        this.providers = providers;
        this.repo = repo;
        this.syncState = syncState;
        this.aliases = aliases;
        this.clock = clock;
    }

    /** Loads persisted teams into memory. Call once at startup before serving. */
    public void load() {
        Map<String, Team> map = new LinkedHashMap<>();
        for (Team t : repo.findAll()) {
            map.put(t.id(), t);
        }
        byId = Map.copyOf(map);
        log.info("Team catalog loaded {} teams", map.size());
    }

    /**
     * Fetches and stores one league's teams. Failures are recorded in sync_state and leave the
     * existing catalog untouched.
     *
     * @return true on success
     */
    public boolean sync(League league) {
        Instant started = clock.instant();
        syncState.recordAttempt(league.id(), SyncState.Kind.TEAMS, started);
        try {
            List<Team> fetched =
                    providers.forLeague(league).fetchTeams(league).stream()
                            .map(aliases::apply)
                            .toList();
            if (fetched.isEmpty()) {
                // An empty list is never right for a team catalog; keep what we have.
                syncState.recordFailure(
                        league.id(), SyncState.Kind.TEAMS, clock.instant(), "empty team list");
                log.warn("Team sync for {} returned no teams", league.id());
                return false;
            }
            repo.upsertAll(fetched, clock.instant());
            synchronized (this) {
                Map<String, Team> map = new LinkedHashMap<>(byId);
                fetched.forEach(t -> map.put(t.id(), t));
                byId = Map.copyOf(map);
            }
            syncState.recordSuccess(league.id(), SyncState.Kind.TEAMS, clock.instant(), null, null);
            log.info("Team sync for {}: {} teams", league.id(), fetched.size());
            return true;
        } catch (ProviderException | RuntimeException e) {
            syncState.recordFailure(
                    league.id(), SyncState.Kind.TEAMS, clock.instant(), e.getMessage());
            log.warn("Team sync for {} failed: {}", league.id(), e.getMessage());
            return false;
        }
    }

    /** Syncs every enabled league whose last successful team sync is older than {@code maxAge}. */
    public void syncStale(Duration maxAge) {
        Instant cutoff = clock.instant().minus(maxAge);
        for (League league : leagues.enabled()) {
            Instant last =
                    syncState
                            .find(league.id(), SyncState.Kind.TEAMS)
                            .map(SyncState::lastSuccessAt)
                            .orElse(null);
            if (last == null || last.isBefore(cutoff)) {
                sync(league);
            }
        }
    }

    /**
     * Records teams that appeared in game payloads but aren't in the catalog (e.g. FCS opponents of
     * FBS schools). Never overwrites catalog entries, whose data is richer.
     */
    public void addFromGames(Collection<Team> teams) {
        List<Team> missing =
                teams.stream().filter(t -> !byId.containsKey(t.id())).map(aliases::apply).toList();
        if (missing.isEmpty()) {
            return;
        }
        repo.insertMissing(missing, clock.instant());
        synchronized (this) {
            Map<String, Team> map = new LinkedHashMap<>(byId);
            missing.forEach(t -> map.putIfAbsent(t.id(), t));
            byId = Map.copyOf(map);
        }
    }

    /** A team in an enabled league. */
    public Optional<Team> find(String id) {
        Team t = byId.get(id);
        return t != null && leagues.find(t.league()).isPresent()
                ? Optional.of(t)
                : Optional.empty();
    }

    public List<Team> byLeague(String leagueId) {
        return byId.values().stream()
                .filter(t -> t.league().equals(leagueId))
                .sorted(Comparator.comparing(Team::name))
                .toList();
    }

    /** Teams in all enabled leagues. */
    public List<Team> all() {
        return byId.values().stream().filter(t -> leagues.find(t.league()).isPresent()).toList();
    }
}
