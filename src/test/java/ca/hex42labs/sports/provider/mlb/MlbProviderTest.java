package ca.hex42labs.sports.provider.mlb;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.provider.UpstreamHttp;
import ca.hex42labs.sports.testing.Fixtures;
import ca.hex42labs.sports.testing.TestLeagues;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

@WireMockTest
class MlbProviderTest {

    @Test
    void mapsTeamsToCanonicalIds() {
        List<Team> teams = MlbProvider.teams(Fixtures.json("mlb/teams"));
        assertThat(teams).hasSize(30);
        Team tor = teams.stream().filter(t -> t.id().equals("mlb:tor")).findFirst().orElseThrow();
        assertThat(tor.name()).isEqualTo("Toronto Blue Jays");
        assertThat(tor.shortName()).isEqualTo("Blue Jays");
        assertThat(tor.providerTeamId()).isEqualTo("141");
        assertThat(tor.logoUrl()).isEqualTo("https://a.espncdn.com/i/teamlogos/mlb/500/tor.png");
        // MLB says AZ / CWS; ESPN (and our ids) say ari / chw.
        assertThat(teams).extracting(Team::id).contains("mlb:ari", "mlb:chw");
    }

    @Test
    void canonicalIdsMatchEspnAbbreviations() {
        Set<String> ours =
                MlbProvider.teams(Fixtures.json("mlb/teams")).stream()
                        .map(Team::id)
                        .collect(Collectors.toSet());
        JsonNode espn =
                Fixtures.json("espn/mlb/teams")
                        .path("sports")
                        .path(0)
                        .path("leagues")
                        .path(0)
                        .path("teams");
        Set<String> theirs =
                StreamSupport.stream(espn.spliterator(), false)
                        .map(
                                t ->
                                        "mlb:"
                                                + t.path("team")
                                                        .path("abbreviation")
                                                        .asText()
                                                        .toLowerCase())
                        .collect(Collectors.toSet());
        assertThat(ours).isEqualTo(theirs);
    }

    @Test
    void upstreamFailureFallsBackToSeed(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(get(urlPathEqualTo("/teams")).willReturn(aResponse().withStatus(500)));
        var provider =
                new MlbProvider(
                        UpstreamHttp.withDefaults(new ObjectMapper(), "test"), wm.getHttpBaseUrl());
        assertThat(provider.fetchTeams(TestLeagues.get("mlb"))).hasSize(30);
    }

    @Test
    void teamIdLookup() {
        assertThat(MlbProvider.teamId(141)).contains("mlb:tor");
        assertThat(MlbProvider.teamId(9999)).isEmpty();
    }
}
