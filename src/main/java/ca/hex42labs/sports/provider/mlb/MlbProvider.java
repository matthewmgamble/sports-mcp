package ca.hex42labs.sports.provider.mlb;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ScheduleResult;
import ca.hex42labs.sports.provider.SeedTeams;
import ca.hex42labs.sports.provider.SportsProvider;
import ca.hex42labs.sports.provider.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MLB Stats API ({@code statsapi.mlb.com/api/v1}). */
public final class MlbProvider implements SportsProvider {

    public static final String ID = "mlb";
    private static final Logger log = LoggerFactory.getLogger(MlbProvider.class);

    /**
     * MLB team id to ESPN abbreviation (canonical team key and logo slug). Ported from Pulseboard;
     * includes the All-Star squads, which have their own ids.
     */
    static final Map<Integer, String> ESPN_ABBR =
            Map.ofEntries(
                    Map.entry(108, "laa"),
                    Map.entry(109, "ari"),
                    Map.entry(110, "bal"),
                    Map.entry(111, "bos"),
                    Map.entry(112, "chc"),
                    Map.entry(113, "cin"),
                    Map.entry(114, "cle"),
                    Map.entry(115, "col"),
                    Map.entry(116, "det"),
                    Map.entry(117, "hou"),
                    Map.entry(118, "kc"),
                    Map.entry(119, "lad"),
                    Map.entry(120, "wsh"),
                    Map.entry(121, "nym"),
                    Map.entry(133, "ath"),
                    Map.entry(134, "pit"),
                    Map.entry(135, "sd"),
                    Map.entry(136, "sea"),
                    Map.entry(137, "sf"),
                    Map.entry(138, "stl"),
                    Map.entry(139, "tb"),
                    Map.entry(140, "tex"),
                    Map.entry(141, "tor"),
                    Map.entry(142, "min"),
                    Map.entry(143, "phi"),
                    Map.entry(144, "atl"),
                    Map.entry(145, "chw"),
                    Map.entry(146, "mia"),
                    Map.entry(147, "nyy"),
                    Map.entry(158, "mil"),
                    Map.entry(159, "al"),
                    Map.entry(160, "nl"));

    private static final String LOGO_URL = "https://a.espncdn.com/i/teamlogos/mlb/500/%s.png";

    private static final String HYDRATE = "&hydrate=linescore,team,seriesStatus";

    /** Gap between per-day fallback requests (they run inside one rate-limiter permit). */
    static final Duration FALLBACK_PACE = Duration.ofSeconds(1);

    private final UpstreamHttp http;
    private final String baseUrl;
    private final Clock clock;
    private final Duration fallbackPace;

    public MlbProvider(UpstreamHttp http, String baseUrl, Clock clock) {
        this(http, baseUrl, clock, FALLBACK_PACE);
    }

    MlbProvider(UpstreamHttp http, String baseUrl, Clock clock, Duration fallbackPace) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clock = clock;
        this.fallbackPace = fallbackPace;
    }

    @Override
    public String id() {
        return ID;
    }

    /** Canonical team id ({@code mlb:tor}) for an MLB team id, if it's one we know. */
    public static Optional<String> teamId(int mlbTeamId) {
        return Optional.ofNullable(ESPN_ABBR.get(mlbTeamId)).map(k -> Ids.team("mlb", k));
    }

    /** Teams from {@code teams?sportId=1}, falling back to the bundled seed. */
    @Override
    public List<Team> fetchTeams(League league) {
        try {
            List<Team> teams = teams(http.getJson(ID, URI.create(baseUrl + "/teams?sportId=1")));
            if (!teams.isEmpty()) {
                return teams;
            }
            log.warn("MLB teams returned nothing; using bundled seed");
        } catch (ProviderException e) {
            log.warn("MLB teams unavailable ({}); using bundled seed", e.getMessage());
        }
        return teams(SeedTeams.load("mlb-teams.json"));
    }

    static List<Team> teams(JsonNode root) {
        List<Team> teams = new ArrayList<>();
        for (JsonNode t : root.path("teams")) {
            team(t).ifPresentOrElse(
                            teams::add,
                            () ->
                                    log.warn(
                                            "Unknown MLB team id {} ({}); add it to ESPN_ABBR",
                                            t.path("id").asInt(-1),
                                            t.path("name").asText()));
        }
        return teams;
    }

    /** One team object (from {@code /teams} or a hydrated schedule); empty if not an MLB club. */
    static Optional<Team> team(JsonNode t) {
        int mlbId = t.path("id").asInt(-1);
        String key = ESPN_ABBR.get(mlbId);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.of(
                new Team(
                        Ids.team("mlb", key),
                        "mlb",
                        ID,
                        String.valueOf(mlbId),
                        t.path("name").asText(key),
                        t.path("teamName").asText(null),
                        t.path("abbreviation").asText(null),
                        t.path("franchiseName").asText(null),
                        String.format(LOGO_URL, key),
                        List.of()));
    }

    /** One date. Single-date queries are the reliable form (ranges intermittently 406). */
    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException {
        JsonNode root =
                http.getJson(
                        ID, URI.create(baseUrl + "/schedule?sportId=1&date=" + bucket + HYDRATE));
        Instant fetchedAt = clock.instant();
        Map<String, Team> teams = new LinkedHashMap<>();
        List<Game> games = mapSchedule(root, fetchedAt, teams);
        return new ProviderBatch(
                league.id(), bucket, games, List.copyOf(teams.values()), fetchedAt);
    }

    /**
     * One range request for the whole window. Pulseboard saw statsapi answer ranges with an
     * intermittent HTTP 406; on that, fall back to one request per day. The 406 is also statsapi's
     * per-IP throttle, so the fallback stops at the first day that 406s instead of spending the
     * rest of the window digging the hole deeper; the remaining days are retried on the next sync.
     */
    @Override
    public ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
            throws ProviderException {
        ScheduleResult.Builder result = new ScheduleResult.Builder();
        try {
            JsonNode root =
                    http.getJson(
                            ID,
                            URI.create(
                                    baseUrl
                                            + "/schedule?sportId=1&startDate="
                                            + from
                                            + "&endDate="
                                            + to
                                            + HYDRATE));
            Instant fetchedAt = clock.instant();
            Map<String, Team> teams = new LinkedHashMap<>();
            result.add(mapSchedule(root, fetchedAt, teams), List.copyOf(teams.values()), fetchedAt);
            return result.build(fetchedAt);
        } catch (ProviderException e) {
            if (!is406(e)) {
                throw e;
            }
            log.info("MLB range {}..{} got 406; falling back to per-day requests", from, to);
        }
        ProviderException throttled = null;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (throttled != null) {
                result.failDay(day, throttled);
                continue;
            }
            try {
                if (!day.equals(from)) {
                    pause();
                }
                result.add(fetchScoreboard(league, day));
            } catch (ProviderException e) {
                result.failDay(day, e);
                if (is406(e)) {
                    throttled = e;
                }
            }
        }
        return result.build(clock.instant());
    }

    /**
     * The club's season (regular season and postseason). The season is the current Eastern year,
     * which is also right in the off-season once next year's schedule is published.
     */
    @Override
    public ScheduleResult fetchTeamSchedule(League league, Team team) throws ProviderException {
        int season =
                LocalDate.now(clock.withZone(java.time.ZoneId.of("America/New_York"))).getYear();
        JsonNode root =
                http.getJson(
                        ID,
                        URI.create(
                                baseUrl
                                        + "/schedule?sportId=1&teamId="
                                        + team.providerTeamId()
                                        + "&season="
                                        + season
                                        + "&gameType=R,F,D,L,W"
                                        + HYDRATE));
        Instant fetchedAt = clock.instant();
        Map<String, Team> teams = new LinkedHashMap<>();
        return new ScheduleResult.Builder()
                .add(mapSchedule(root, fetchedAt, teams), List.copyOf(teams.values()), fetchedAt)
                .build(fetchedAt);
    }

    @Override
    public boolean hasPerGameFeed() {
        return true;
    }

    /** Live games only: {@code game/{pk}/linescore} is ~1 KB and current. */
    @Override
    public Optional<Game> refreshGame(League league, Game game) throws ProviderException {
        if (game.status() != ca.hex42labs.sports.domain.GameStatus.LIVE) {
            return Optional.empty();
        }
        JsonNode ls =
                http.getJson(
                        ID, URI.create(baseUrl + "/game/" + game.providerEventId() + "/linescore"));
        return Optional.of(MlbMapper.applyLinescore(game, ls, clock.instant()));
    }

    private void pause() throws ProviderException {
        if (fallbackPace.isZero()) {
            return;
        }
        try {
            Thread.sleep(fallbackPace);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(ID, ProviderException.Kind.NETWORK, "interrupted", e);
        }
    }

    private static boolean is406(ProviderException e) {
        return e.kind() == ProviderException.Kind.HTTP_STATUS && e.status() == 406;
    }

    private static List<Game> mapSchedule(
            JsonNode root, Instant fetchedAt, Map<String, Team> teams) {
        List<Game> games = new ArrayList<>();
        for (JsonNode date : root.path("dates")) {
            for (JsonNode g : date.path("games")) {
                MlbMapper.game(g, fetchedAt, teams).ifPresent(games::add);
            }
        }
        return games;
    }
}
