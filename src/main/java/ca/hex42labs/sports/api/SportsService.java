package ca.hex42labs.sports.api;

import ca.hex42labs.sports.api.dto.Envelope;
import ca.hex42labs.sports.api.dto.GameDto;
import ca.hex42labs.sports.api.dto.LeagueDto;
import ca.hex42labs.sports.api.dto.Meta;
import ca.hex42labs.sports.api.dto.TeamDto;
import ca.hex42labs.sports.application.Refreshed;
import ca.hex42labs.sports.application.ScoreRefreshCoordinator;
import ca.hex42labs.sports.application.TeamCatalog;
import ca.hex42labs.sports.application.TeamResolver;
import ca.hex42labs.sports.application.TeamResolver.Resolution;
import ca.hex42labs.sports.config.LeagueRegistry;
import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.GameStatus;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.observability.SportsMetrics;
import ca.hex42labs.sports.persistence.GameRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The one service behind both REST and MCP. Inputs arrive as the raw strings a client sent;
 * validation and messages live here so both interfaces reject the same things the same way.
 */
public final class SportsService {

    public static final int MAX_RANGE_DAYS = 31;
    public static final int DEFAULT_GAME_LIMIT = 25;
    public static final int MAX_GAME_LIMIT = 100;
    public static final int DEFAULT_TEAM_LIMIT = 50;
    public static final int MAX_TEAM_LIMIT = 400;
    static final int SCHEDULE_PAST = 3;
    static final int SCHEDULE_NEXT = 5;

    /** How far a team schedule looks when no range is given. */
    static final Duration SCHEDULE_REACH = Duration.ofDays(400);

    private static final Map<String, String> ATTRIBUTION =
            Map.of(
                    "espn", "ESPN (unofficial public API)",
                    "nhl", "NHL (unofficial public API)",
                    "mlb", "MLB Advanced Media (unofficial public API; data (c) MLBAM)");

    private final LeagueRegistry leagues;
    private final TeamCatalog teams;
    private final TeamResolver resolver;
    private final GameRepository games;
    private final ScoreRefreshCoordinator scores;
    private final Clock clock;
    private final ZoneId defaultZone;
    private final RefreshGate refreshGate;

    /** Decides whether this caller may bypass freshness windows right now. */
    @FunctionalInterface
    public interface RefreshGate {
        boolean allow();

        RefreshGate ALWAYS = () -> true;
    }

    public SportsService(
            LeagueRegistry leagues,
            TeamCatalog teams,
            TeamResolver resolver,
            GameRepository games,
            ScoreRefreshCoordinator scores,
            Clock clock,
            ZoneId defaultZone,
            RefreshGate refreshGate) {
        this.leagues = leagues;
        this.teams = teams;
        this.resolver = resolver;
        this.games = games;
        this.scores = scores;
        this.clock = clock;
        this.defaultZone = defaultZone;
        this.refreshGate = refreshGate;
    }

    /**
     * {@code refresh=true} beyond the caller's refresh budget quietly becomes a normal request:
     * they still get fresh-enough data, just not forced.
     */
    private boolean forced(Boolean refresh) {
        return Boolean.TRUE.equals(refresh) && refreshGate.allow();
    }

    // ---------------------------------------------------------------- operations

    public Envelope<List<LeagueDto>> leagues() {
        List<LeagueDto> data =
                leagues.enabled().stream()
                        .map(l -> new LeagueDto(l.id(), l.displayName(), l.sport(), l.provider()))
                        .toList();
        Set<String> sources =
                data.stream().map(LeagueDto::source).collect(Collectors.toCollection(TreeSet::new));
        return new Envelope<>(
                data, meta(sources, clock.instant(), false, null, null, data.size(), null));
    }

    /**
     * @param league restrict to one league (required unless {@code search} is given)
     * @param search name, city, abbreviation or nickname
     */
    public Envelope<List<TeamDto>> teams(String league, String search, Integer limit) {
        String leagueId = blank(league) ? null : league(league).id();
        if (leagueId == null && blank(search)) {
            throw ApiException.invalid(
                    "Give a league ("
                            + String.join(", ", leagues.enabledIds())
                            + ") or a search term.");
        }
        int max = limit(limit, DEFAULT_TEAM_LIMIT, MAX_TEAM_LIMIT);
        List<Team> found = resolver.search(blank(search) ? null : search, leagueId, max + 1);
        boolean truncated = found.size() > max;
        List<TeamDto> data = found.stream().limit(max).map(SportsService::team).toList();
        Set<String> sources =
                data.stream()
                        .map(t -> providerOf(t.league()))
                        .collect(Collectors.toCollection(TreeSet::new));
        return new Envelope<>(
                data, meta(sources, clock.instant(), false, null, null, data.size(), truncated));
    }

    /**
     * Games for a league or team over a date range in {@code timezone}.
     *
     * @param date a day: YYYY-MM-DD, "today", "tomorrow" or "yesterday" (default today)
     * @param from first day of a range (inclusive), instead of date
     * @param to last day of a range (inclusive)
     */
    public Envelope<List<GameDto>> games(
            String league,
            String team,
            String date,
            String from,
            String to,
            String status,
            String timezone,
            Integer limit) {
        ZoneId zone = zone(timezone);
        League leagueHint = blank(league) ? null : league(league);
        Team t = blank(team) ? null : team(team, leagueHint);
        if (leagueHint == null && t == null) {
            throw ApiException.invalid(
                    "Give a league or a team. Leagues: "
                            + String.join(", ", leagues.enabledIds())
                            + ".");
        }
        GameStatus statusFilter = status(status);
        int max = limit(limit, DEFAULT_GAME_LIMIT, MAX_GAME_LIMIT);
        DateRange range = range(date, from, to, zone);

        List<String> notes = new ArrayList<>();
        if (t != null) {
            scores.ensureTeamSchedule(t, false).ifPresent(notes::add);
        }
        String leagueId = t != null ? t.league() : leagueHint.id();
        List<Game> found =
                games.find(
                        leagueId,
                        t == null ? null : t.id(),
                        statusFilter,
                        range.start(),
                        range.end(),
                        false,
                        max + 1);
        if (found.isEmpty() && t == null) {
            found = probeEmptyDays(leagueHint, range, statusFilter, max + 1);
        }
        boolean truncated = found.size() > max;
        List<Game> page = found.stream().limit(max).toList();
        return gamesEnvelope(scores.ensureFresh(page, false), zone, truncated, notes);
    }

    /** Games in progress now. */
    public Envelope<List<GameDto>> live(
            String league, String team, Boolean refresh, String timezone) {
        ZoneId zone = zone(timezone);
        League leagueHint = blank(league) ? null : league(league);
        Team t = blank(team) ? null : team(team, leagueHint);
        String leagueId = t != null ? t.league() : leagueHint == null ? null : leagueHint.id();
        Refreshed<List<Game>> r = scores.live(leagueId, t == null ? null : t.id(), forced(refresh));
        return gamesEnvelope(r, zone, null, List.of());
    }

    public Envelope<GameDto> game(String gameId, Boolean refresh, String timezone) {
        ZoneId zone = zone(timezone);
        String id = blank(gameId) ? "" : gameId.trim().toLowerCase(Locale.ROOT);
        if (!Ids.isWellFormed(id)) {
            throw ApiException.invalid(
                    "gameId must look like 'nfl:401872994' (from a games or schedule result).");
        }
        if (leagues.find(Ids.leagueOf(id)).isEmpty()) {
            throw ApiException.invalid(
                    "Unknown league in gameId '"
                            + gameId
                            + "'. Leagues: "
                            + String.join(", ", leagues.enabledIds())
                            + ".");
        }
        Refreshed<Optional<Game>> r = scores.game(id, forced(refresh));
        if (r.stale()) {
            SportsMetrics.staleResponse("game");
        }
        Game g =
                r.data()
                        .orElseThrow(
                                () ->
                                        ApiException.notFound(
                                                "game_not_found",
                                                "No game "
                                                        + id
                                                        + ". Game ids come from games, live or schedule results."));
        Map<String, Team> lookup = teamLookup(List.of(g));
        return new Envelope<>(
                game(g, zone, lookup),
                meta(r.sources(), r.asOf(), r.stale(), r.staleReason(), zone, null, null));
    }

    /** A team's schedule. Without a range: its last 3 and next 5 games. */
    public Envelope<List<GameDto>> teamSchedule(
            String team, String league, String from, String to, Integer limit, String timezone) {
        ZoneId zone = zone(timezone);
        if (blank(team)) {
            throw ApiException.invalid(
                    "team is required: a team id like 'nhl:tor' or a name like 'Maple Leafs'.");
        }
        Team t = team(team, blank(league) ? null : league(league));
        List<String> notes = new ArrayList<>();
        scores.ensureTeamSchedule(t, false).ifPresent(notes::add);
        Instant now = clock.instant();
        List<Game> found;
        Boolean truncated = null;
        if (blank(from) && blank(to)) {
            List<Game> past =
                    new ArrayList<>(
                            games.find(
                                    null,
                                    t.id(),
                                    null,
                                    now.minus(SCHEDULE_REACH),
                                    now,
                                    true,
                                    SCHEDULE_PAST));
            past.sort(Comparator.comparing(Game::startsAt));
            List<Game> next =
                    games.find(
                            null,
                            t.id(),
                            null,
                            now,
                            now.plus(SCHEDULE_REACH),
                            false,
                            SCHEDULE_NEXT);
            found = new ArrayList<>(past);
            found.addAll(next);
        } else {
            int max = limit(limit, 20, MAX_GAME_LIMIT);
            DateRange range = range(null, from, to, zone);
            found = games.find(null, t.id(), null, range.start(), range.end(), false, max + 1);
            truncated = found.size() > max;
            found = found.stream().limit(max).toList();
        }
        return gamesEnvelope(scores.ensureFresh(found, false), zone, truncated, notes);
    }

    // ---------------------------------------------------------------- mapping

    private Envelope<List<GameDto>> gamesEnvelope(
            Refreshed<List<Game>> r, ZoneId zone, Boolean truncated, List<String> notes) {
        Map<String, Team> lookup = teamLookup(r.data());
        List<GameDto> data = r.data().stream().map(g -> game(g, zone, lookup)).toList();
        Set<String> sources = new TreeSet<>(r.sources());
        if (sources.isEmpty()) {
            data.stream().map(GameDto::source).forEach(sources::add);
        }
        boolean stale = r.stale() || !notes.isEmpty() && data.isEmpty();
        if (stale) {
            SportsMetrics.staleResponse("games");
        }
        String reason = r.staleReason();
        if (stale && reason == null && !notes.isEmpty()) {
            reason = String.join("; ", notes);
        }
        return new Envelope<>(
                data, meta(sources, r.asOf(), stale, reason, zone, data.size(), truncated));
    }

    private Map<String, Team> teamLookup(List<Game> gs) {
        Map<String, Team> m = new LinkedHashMap<>();
        for (Game g : gs) {
            teams.find(g.homeTeamId()).ifPresent(t -> m.put(t.id(), t));
            teams.find(g.awayTeamId()).ifPresent(t -> m.put(t.id(), t));
        }
        return m;
    }

    static GameDto game(Game g, ZoneId zone, Map<String, Team> teams) {
        return new GameDto(
                g.id(),
                g.league(),
                g.status().name(),
                g.statusDetail(),
                g.startsAt(),
                g.startsAt()
                        .atZone(zone)
                        .toOffsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                g.period(),
                g.periodType() == null ? null : g.periodType().name(),
                g.clock(),
                side(g.homeTeamId(), teams.get(g.homeTeamId()), g.homeScore(), g.homeShootout()),
                side(g.awayTeamId(), teams.get(g.awayTeamId()), g.awayScore(), g.awayShootout()),
                g.venue(),
                g.broadcasts(),
                g.situation() == null
                        ? null
                        : new GameDto.Situation(
                                g.situation().possessionTeamId(),
                                g.situation().downDistance(),
                                g.situation().fieldPosition(),
                                g.situation().redZone()),
                g.provider(),
                g.fetchedAt());
    }

    private static GameDto.Side side(String id, Team t, Integer score, Integer shootout) {
        return t == null
                ? new GameDto.Side(id, id, null, null, score, shootout)
                : new GameDto.Side(
                        t.id(), t.name(), t.abbreviation(), t.logoUrl(), score, shootout);
    }

    static TeamDto team(Team t) {
        return new TeamDto(
                t.id(),
                t.league(),
                t.name(),
                t.shortName(),
                t.abbreviation(),
                t.location(),
                t.logoUrl());
    }

    private Meta meta(
            Set<String> sources,
            Instant asOf,
            boolean stale,
            String reason,
            ZoneId zone,
            Integer count,
            Boolean truncated) {
        List<String> src = List.copyOf(sources);
        String attribution =
                src.isEmpty()
                        ? null
                        : "Data: "
                                + src.stream()
                                        .map(s -> ATTRIBUTION.getOrDefault(s, s))
                                        .collect(Collectors.joining("; "))
                                + ". Not affiliated with or endorsed by the data owners.";
        return new Meta(
                src,
                asOf,
                stale,
                stale ? reason : null,
                attribution,
                zone == null ? null : zone.getId(),
                count,
                truncated);
    }

    // ---------------------------------------------------------------- input parsing

    private League league(String raw) {
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return leagues.find(id)
                .orElseThrow(
                        () ->
                                ApiException.invalid(
                                        "Unknown league '"
                                                + raw
                                                + "'. Leagues: "
                                                + String.join(", ", leagues.enabledIds())
                                                + "."));
    }

    private Team team(String raw, League hint) {
        Resolution r = resolver.resolve(raw, hint == null ? null : hint.id());
        return switch (r) {
            case Resolution.Match m -> m.team();
            case Resolution.Ambiguous a -> {
                List<Map<String, String>> candidates =
                        a.candidates().stream()
                                .map(
                                        c ->
                                                Map.of(
                                                        "id",
                                                        c.id(),
                                                        "name",
                                                        c.name(),
                                                        "league",
                                                        c.league()))
                                .toList();
                yield throwAmbiguous(raw, candidates);
            }
            case Resolution.NotFound n ->
                    throw ApiException.notFound(
                            "team_not_found",
                            "No team matches '"
                                    + raw
                                    + "'"
                                    + (hint == null ? "" : " in " + hint.id())
                                    + ". Use sports_list_teams (GET /api/v1/teams?search=...) to find team ids.");
        };
    }

    private static Team throwAmbiguous(String raw, List<Map<String, String>> candidates) {
        String names =
                candidates.stream()
                        .map(c -> c.get("name") + " (" + c.get("id") + ")")
                        .collect(Collectors.joining(", "));
        throw new ApiException(
                400,
                "ambiguous_team",
                "'"
                        + raw
                        + "' matches several teams: "
                        + names
                        + ". Pass a team id or add a league.",
                Map.of("candidates", candidates));
    }

    private ZoneId zone(String raw) {
        if (blank(raw)) {
            return defaultZone;
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            throw ApiException.invalid(
                    "Unknown timezone '"
                            + raw
                            + "'. Use an IANA name like America/Toronto or Europe/London.");
        }
    }

    private static GameStatus status(String raw) {
        if (blank(raw)) {
            return null;
        }
        try {
            return GameStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid(
                    "Unknown status '"
                            + raw
                            + "'. Use one of "
                            + Arrays.toString(GameStatus.values())
                            + ".");
        }
    }

    private static int limit(Integer raw, int def, int max) {
        if (raw == null) {
            return def;
        }
        if (raw < 1 || raw > max) {
            throw ApiException.invalid("limit must be between 1 and " + max + ".");
        }
        return raw;
    }

    record DateRange(LocalDate first, LocalDate last, Instant start, Instant end) {}

    DateRange range(String date, String from, String to, ZoneId zone) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first;
        LocalDate last;
        if (!blank(date)) {
            if (!blank(from) || !blank(to)) {
                throw ApiException.invalid("Give either date or from/to, not both.");
            }
            first = last = day(date, today, "date");
        } else if (blank(from) && blank(to)) {
            first = last = today;
        } else {
            first = blank(from) ? today : day(from, today, "from");
            last = blank(to) ? first : day(to, today, "to");
        }
        if (last.isBefore(first)) {
            throw ApiException.invalid("to (" + last + ") is before from (" + first + ").");
        }
        if (ChronoUnit.DAYS.between(first, last) >= MAX_RANGE_DAYS) {
            throw ApiException.invalid("Date range is limited to " + MAX_RANGE_DAYS + " days.");
        }
        return new DateRange(
                first,
                last,
                first.atStartOfDay(zone).toInstant(),
                last.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private static LocalDate day(String raw, LocalDate today, String field) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "today" -> today;
            case "tomorrow" -> today.plusDays(1);
            case "yesterday" -> today.minusDays(1);
            default -> {
                try {
                    yield LocalDate.parse(raw.trim());
                } catch (DateTimeParseException e) {
                    throw ApiException.invalid(
                            field
                                    + " must be YYYY-MM-DD, today, tomorrow or yesterday (got '"
                                    + raw
                                    + "').");
                }
            }
        };
    }

    /**
     * Nothing stored for a league in the range: if the range touches yesterday-tomorrow, ask
     * upstream for those days (rate-limited by the coordinator's empty-day rule).
     */
    private List<Game> probeEmptyDays(League league, DateRange range, GameStatus status, int max) {
        LocalDate today = scores.today();
        boolean probed = false;
        for (LocalDate d = today.minusDays(1); !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
            Instant dayStart = d.atStartOfDay(ScoreRefreshCoordinator.BUCKET_ZONE).toInstant();
            Instant dayEnd =
                    d.plusDays(1).atStartOfDay(ScoreRefreshCoordinator.BUCKET_ZONE).toInstant();
            if (dayEnd.isAfter(range.start()) && dayStart.isBefore(range.end())) {
                scores.day(league, d, false);
                probed = true;
            }
        }
        return probed
                ? games.find(league.id(), null, status, range.start(), range.end(), false, max)
                : List.of();
    }

    private String providerOf(String leagueId) {
        return leagues.find(leagueId).map(League::provider).orElse(leagueId);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
