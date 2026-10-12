package ca.hex42labs.sports.provider.espn;

import ca.hex42labs.sports.application.TeamDetails;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.ResilientProvider;
import ca.hex42labs.sports.provider.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;

/**
 * Team details from ESPN's team schedule endpoint, for every league: NHL and MLB come from their
 * own APIs, but ESPN has the colours and a one-line standing for them too. The team's key in our
 * canonical id is the one ESPN takes (abbreviation or ESPN id; "utah" for the NHL's Mammoth).
 */
public final class EspnTeamDetails implements TeamDetails.Source {

    /** ESPN paths for leagues whose games come from another provider. */
    static final Map<String, String> OTHER_PATHS =
            Map.of("nhl", "hockey/nhl", "mlb", "baseball/mlb");

    private final UpstreamHttp http;
    private final String baseUrl;
    private final ResilientProvider espn;

    /**
     * @param espn the ESPN provider, whose rate limit and breaker these calls share
     */
    public EspnTeamDetails(UpstreamHttp http, String baseUrl, ResilientProvider espn) {
        this.http = http;
        this.espn = espn;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public TeamDetails.Details fetch(League league, Team team) throws ProviderException {
        String path =
                EspnProvider.ID.equals(league.provider())
                        ? league.providerPath()
                        : OTHER_PATHS.get(league.id());
        if (path == null) {
            return new TeamDetails.Details(null, null, null);
        }
        String key = team.id().substring(team.id().indexOf(':') + 1);
        URI uri = URI.create(baseUrl + "/" + path + "/teams/" + key + "/schedule");
        return details(espn.guard(() -> http.getJson(EspnProvider.ID, uri)).path("team"));
    }

    static TeamDetails.Details details(JsonNode t) {
        String record = text(t, "recordSummary");
        // "0-0" is the playoffs' record for a team that isn't in them.
        if (record != null && record.matches("0(-0)+")) {
            record = null;
        }
        return new TeamDetails.Details(text(t, "color"), record, text(t, "standingSummary"));
    }

    private static String text(JsonNode node, String field) {
        String s = node.path(field).asText("").strip();
        return s.isEmpty() ? null : s;
    }
}
