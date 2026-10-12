package ca.hex42labs.sports.provider.nhl;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** NHL web API ({@code api-web.nhle.com/v1}). */
public final class NhlProvider implements SportsProvider {

    public static final String ID = "nhl";
    private static final Logger log = LoggerFactory.getLogger(NhlProvider.class);

    /** NHL abbreviation to ESPN abbreviation, only where they differ (from Pulseboard). */
    static final Map<String, String> ESPN_ABBR =
            Map.of("LAK", "la", "SJS", "sj", "NJD", "nj", "TBL", "tb", "UTA", "utah");

    private static final String LOGO_URL = "https://a.espncdn.com/i/teamlogos/nhl/500/%s.png";

    /** Safety bound on weekly pages per window (a 16-day window needs 3). */
    private static final int MAX_PAGES = 8;

    private final UpstreamHttp http;
    private final String baseUrl;
    private final Clock clock;

    public NhlProvider(UpstreamHttp http, String baseUrl, Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    /** Canonical team id ({@code nhl:tor}, {@code nhl:la}) for an NHL abbreviation. */
    public static String teamId(String nhlAbbrev) {
        return Ids.team("nhl", espnKey(nhlAbbrev));
    }

    static String espnKey(String nhlAbbrev) {
        return ESPN_ABBR.getOrDefault(nhlAbbrev, nhlAbbrev.toLowerCase(Locale.ROOT));
    }

    /**
     * Teams from {@code standings/now}. Standings are empty between seasons, and team lists barely
     * change, so an empty or failed response falls back to the bundled seed.
     */
    @Override
    public List<Team> fetchTeams(League league) {
        try {
            List<Team> teams =
                    teamsFromStandings(http.getJson(ID, URI.create(baseUrl + "/standings/now")));
            if (!teams.isEmpty()) {
                return teams;
            }
            log.warn("NHL standings returned no teams; using bundled seed");
        } catch (ProviderException e) {
            log.warn("NHL standings unavailable ({}); using bundled seed", e.getMessage());
        }
        return teamsFromStandings(SeedTeams.load("nhl-standings.json"));
    }

    static List<Team> teamsFromStandings(JsonNode root) {
        List<Team> teams = new ArrayList<>();
        for (JsonNode s : root.path("standings")) {
            String abbrev = s.path("teamAbbrev").path("default").asText(null);
            if (abbrev == null || abbrev.isBlank()) {
                continue;
            }
            String key = espnKey(abbrev);
            teams.add(
                    new Team(
                            Ids.team("nhl", key),
                            "nhl",
                            ID,
                            abbrev,
                            s.path("teamName").path("default").asText(abbrev),
                            s.path("teamCommonName").path("default").asText(null),
                            abbrev,
                            s.path("placeName").path("default").asText(null),
                            String.format(LOGO_URL, key),
                            List.of()));
        }
        return teams;
    }

    /** {@code score/{date}}: every game that day, with live clocks, in one call. */
    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException {
        JsonNode root = http.getJson(ID, URI.create(baseUrl + "/score/" + bucket));
        Instant fetchedAt = clock.instant();
        Map<String, Team> teams = new LinkedHashMap<>();
        List<Game> games = new ArrayList<>();
        for (JsonNode g : root.path("games")) {
            NhlMapper.game(g, fetchedAt, teams).ifPresent(games::add);
        }
        return new ProviderBatch(
                league.id(), bucket, games, List.copyOf(teams.values()), fetchedAt);
    }

    /**
     * {@code schedule/{date}} returns a 7-day {@code gameWeek} plus {@code nextStartDate}; page
     * through until the window is covered. Days outside the window are dropped.
     */
    @Override
    public ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
            throws ProviderException {
        ScheduleResult.Builder result = new ScheduleResult.Builder();
        LocalDate page = from;
        for (int i = 0; i < MAX_PAGES && page != null && !page.isAfter(to); i++) {
            JsonNode root;
            try {
                root = http.getJson(ID, URI.create(baseUrl + "/schedule/" + page));
            } catch (ProviderException e) {
                result.fail(page.toString(), e);
                page = page.plusDays(7);
                continue;
            }
            Instant fetchedAt = clock.instant();
            Map<String, Team> teams = new LinkedHashMap<>();
            List<Game> games = new ArrayList<>();
            for (JsonNode day : root.path("gameWeek")) {
                LocalDate date = LocalDate.parse(day.path("date").asText());
                if (date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                for (JsonNode g : day.path("games")) {
                    NhlMapper.game(g, fetchedAt, teams).ifPresent(games::add);
                }
            }
            result.add(games, List.copyOf(teams.values()), fetchedAt);
            String next = root.path("nextStartDate").asText(null);
            if (next == null) {
                break; // nothing scheduled after this page (off-season)
            }
            // nextStartDate skips empty weeks. If it ever fails to move forward, stop rather
            // than loop: the window is covered as far as upstream can tell us.
            LocalDate nextPage = LocalDate.parse(next);
            if (!nextPage.isAfter(page)) {
                break;
            }
            page = nextPage;
        }
        return result.build(clock.instant());
    }
}
