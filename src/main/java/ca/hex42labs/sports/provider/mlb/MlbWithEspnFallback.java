package ca.hex42labs.sports.provider.mlb;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.observability.SportsMetrics;
import ca.hex42labs.sports.provider.GameLinker;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.SportsProvider;
import ca.hex42labs.sports.provider.espn.EspnProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MLB from statsapi, with ESPN filling in whatever statsapi refuses. statsapi answers some hosts'
 * schedule requests with HTTP 406 for anything but today (seen from the hosted server, never from a
 * home connection), so the live path stays on statsapi (scoreboard plus the per-game linescore) and
 * the rest of the schedule comes from ESPN when needed.
 *
 * <p>Game ids stay statsapi's: ESPN games are linked to the matching statsapi game through {@link
 * GameLinker}, or get a provisional id until statsapi sees them. ESPN's team objects are dropped;
 * the catalog stays statsapi's (both use ESPN abbreviations as team keys, so team ids agree).
 */
public final class MlbWithEspnFallback implements SportsProvider {

    private static final Logger log = LoggerFactory.getLogger(MlbWithEspnFallback.class);

    static final String ESPN_PATH = "baseball/mlb";

    /** Real clubs only: ESPN lists unseeded postseason games as TBD vs TBD. */
    private static final Set<String> KNOWN_TEAMS =
            MlbProvider.ESPN_ABBR.values().stream()
                    .map(k -> Ids.team("mlb", k))
                    .collect(Collectors.toUnmodifiableSet());

    private final SportsProvider mlb;
    private final SportsProvider espn;
    private final GameLinker linker;
    private final Clock clock;

    /**
     * @param mlb statsapi provider (normally rate-limited/circuit-broken)
     * @param espn ESPN provider (likewise; shared with the ESPN leagues)
     */
    public MlbWithEspnFallback(
            SportsProvider mlb, SportsProvider espn, GameLinker linker, Clock clock) {
        this.mlb = mlb;
        this.espn = espn;
        this.linker = linker;
        this.clock = clock;
    }

    @Override
    public String id() {
        return mlb.id();
    }

    @Override
    public List<Team> fetchTeams(League league) throws ProviderException {
        return mlb.fetchTeams(league); // falls back to the bundled seed by itself
    }

    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException {
        ProviderBatch batch;
        try {
            batch = mlb.fetchScoreboard(league, bucket);
        } catch (ProviderException e) {
            return espnDay(league, bucket, e);
        }
        linker.adopt(league, espn.id(), batch.games());
        return batch;
    }

    /**
     * statsapi first; any days it couldn't deliver (or the whole window, if it failed outright)
     * come from ESPN's day scoreboards.
     */
    @Override
    public ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
            throws ProviderException {
        ScheduleResult primary = null;
        ProviderException primaryError = null;
        List<LocalDate> missing = new ArrayList<>();
        try {
            primary = mlb.fetchSchedule(league, from, to);
            missing.addAll(primary.failedDays());
        } catch (ProviderException e) {
            primaryError = e;
            from.datesUntil(to.plusDays(1)).forEach(missing::add);
        }
        if (primary != null) {
            linker.adopt(league, espn.id(), primary.games());
            if (missing.isEmpty()) {
                return primary;
            }
        }
        ScheduleResult.Builder result = new ScheduleResult.Builder();
        if (primary != null) {
            result.add(primary.games(), primary.teams(), primary.fetchedAt());
        }
        for (LocalDate day : missing) {
            try {
                ProviderBatch b = espn.fetchScoreboard(espnLeague(league), day);
                result.add(linked(league, b.games()), List.of(), b.fetchedAt());
                SportsMetrics.fallback(league.id(), "schedule_day", "ok");
            } catch (ProviderException e) {
                SportsMetrics.fallback(league.id(), "schedule_day", "failed");
                result.failDay(day, e);
            }
        }
        if (!missing.isEmpty()) {
            log.info(
                    "MLB {}..{}: {} day(s) from ESPN after statsapi failed ({})",
                    from,
                    to,
                    missing.size(),
                    primaryError != null ? primaryError.getMessage() : "per-day failures");
        }
        try {
            return result.build(clock.instant());
        } catch (ProviderException allFailed) {
            throw primaryError != null ? primaryError : allFailed;
        }
    }

    @Override
    public ScheduleResult fetchTeamSchedule(League league, Team team) throws ProviderException {
        try {
            return mlb.fetchTeamSchedule(league, team);
        } catch (ProviderException e) {
            try {
                ScheduleResult r = espn.fetchTeamSchedule(espnLeague(league), espnTeam(team));
                SportsMetrics.fallback(league.id(), "team", "ok");
                return new ScheduleResult(
                        linked(league, r.games()), List.of(), r.fetchedAt(), r.failures());
            } catch (ProviderException e2) {
                SportsMetrics.fallback(league.id(), "team", "failed");
                e.addSuppressed(e2);
                throw e;
            }
        }
    }

    /** Only statsapi has the per-game linescore; provisional ESPN games have nothing to refresh. */
    @Override
    public Optional<Game> refreshGame(League league, Game game) throws ProviderException {
        return mlb.id().equals(game.provider()) ? mlb.refreshGame(league, game) : Optional.empty();
    }

    @Override
    public boolean hasPerGameFeed() {
        return mlb.hasPerGameFeed();
    }

    private ProviderBatch espnDay(League league, LocalDate day, ProviderException primaryError)
            throws ProviderException {
        try {
            ProviderBatch b = espn.fetchScoreboard(espnLeague(league), day);
            SportsMetrics.fallback(league.id(), "scoreboard", "ok");
            log.info("MLB {} from ESPN after statsapi failed: {}", day, primaryError.getMessage());
            return new ProviderBatch(
                    league.id(), day, linked(league, b.games()), List.of(), b.fetchedAt());
        } catch (ProviderException e) {
            SportsMetrics.fallback(league.id(), "scoreboard", "failed");
            primaryError.addSuppressed(e);
            throw primaryError;
        }
    }

    private List<Game> linked(League league, List<Game> espnGames) {
        List<Game> known =
                espnGames.stream()
                        .filter(
                                g ->
                                        KNOWN_TEAMS.contains(g.homeTeamId())
                                                && KNOWN_TEAMS.contains(g.awayTeamId()))
                        .toList();
        return linker.link(league, mlb.id(), known);
    }

    /** The MLB league as ESPN's provider sees it (same id, team-key style and logo slug). */
    static League espnLeague(League mlbLeague) {
        return new League(
                mlbLeague.id(),
                mlbLeague.displayName(),
                mlbLeague.sport(),
                EspnProvider.ID,
                ESPN_PATH,
                "",
                "",
                mlbLeague.logoSlug(),
                mlbLeague.teamKeyStyle(),
                mlbLeague.regulationPeriods(),
                mlbLeague.displayTimezone(),
                mlbLeague.enabled());
    }

    /** ESPN's team endpoints accept the abbreviation, which is our team key. */
    static Team espnTeam(Team team) {
        String key = team.id().substring(team.id().indexOf(':') + 1);
        return new Team(
                team.id(),
                team.league(),
                EspnProvider.ID,
                key,
                team.name(),
                team.shortName(),
                team.abbreviation(),
                team.location(),
                team.logoUrl(),
                team.aliases());
    }
}
