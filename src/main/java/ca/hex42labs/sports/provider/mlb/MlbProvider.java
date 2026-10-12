package ca.hex42labs.sports.provider.mlb;

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

    private final UpstreamHttp http;
    private final String baseUrl;

    public MlbProvider(UpstreamHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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
            int mlbId = t.path("id").asInt(-1);
            String key = ESPN_ABBR.get(mlbId);
            if (key == null) {
                log.warn(
                        "Unknown MLB team id {} ({}); add it to ESPN_ABBR",
                        mlbId,
                        t.path("name").asText());
                continue;
            }
            teams.add(
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
        return teams;
    }

    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) {
        throw new UnsupportedOperationException("MLB scoreboard arrives in Sprint 2");
    }
}
