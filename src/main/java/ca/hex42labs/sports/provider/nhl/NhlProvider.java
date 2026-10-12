package ca.hex42labs.sports.provider.nhl;

import ca.hex42labs.sports.domain.Ids;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.SeedTeams;
import ca.hex42labs.sports.provider.SportsProvider;
import ca.hex42labs.sports.provider.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
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

    private final UpstreamHttp http;
    private final String baseUrl;

    public NhlProvider(UpstreamHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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

    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) {
        throw new UnsupportedOperationException("NHL scoreboard arrives in Sprint 2");
    }
}
