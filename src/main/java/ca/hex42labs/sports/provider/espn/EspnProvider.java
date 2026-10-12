package ca.hex42labs.sports.provider.espn;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.ProviderBatch;
import ca.hex42labs.sports.provider.ProviderException;
import ca.hex42labs.sports.provider.SportsProvider;
import ca.hex42labs.sports.provider.UpstreamHttp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** ESPN site API ({@code site.api.espn.com/apis/site/v2/sports}). */
public final class EspnProvider implements SportsProvider {

    public static final String ID = "espn";
    private static final DateTimeFormatter BUCKET = DateTimeFormatter.BASIC_ISO_DATE;

    private final UpstreamHttp http;
    private final String baseUrl;
    private final Clock clock;

    public EspnProvider(UpstreamHttp http, String baseUrl, Clock clock) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Team> fetchTeams(League league) throws ProviderException {
        JsonNode root = http.getJson(ID, uri(league, "teams", league.teamsQuery()));
        return EspnMapper.teams(league, root);
    }

    /**
     * One US-Eastern day. Single dates only: ESPN answers {@code dates=A-B} ranges with HTTP 400.
     */
    @Override
    public ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException {
        String query = join("dates=" + BUCKET.format(bucket), league.scoreboardQuery());
        JsonNode root = http.getJson(ID, uri(league, "scoreboard", query));
        Instant fetchedAt = clock.instant();
        EspnMapper.Scoreboard sb = EspnMapper.scoreboard(league, root, fetchedAt);
        return new ProviderBatch(league.id(), bucket, sb.games(), sb.teams(), fetchedAt);
    }

    // League paths and queries come from the registry allowlist, never from user input.
    private URI uri(League league, String endpoint, String query) {
        String url = baseUrl + "/" + league.providerPath() + "/" + endpoint;
        return URI.create(query == null || query.isBlank() ? url : url + "?" + query);
    }

    private static String join(String a, String b) {
        return b == null || b.isBlank() ? a : a + "&" + b;
    }
}
