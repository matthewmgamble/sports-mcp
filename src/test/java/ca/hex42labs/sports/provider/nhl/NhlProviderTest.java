package ca.hex42labs.sports.provider.nhl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
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
class NhlProviderTest {

    private static NhlProvider provider(WireMockRuntimeInfo wm) {
        return new NhlProvider(
                UpstreamHttp.withDefaults(new ObjectMapper(), "test"), wm.getHttpBaseUrl());
    }

    @Test
    void mapsStandingsToCanonicalTeams() {
        List<Team> teams = NhlProvider.teamsFromStandings(Fixtures.json("nhl/standings-now"));
        assertThat(teams).hasSize(32);
        Team tor = teams.stream().filter(t -> t.id().equals("nhl:tor")).findFirst().orElseThrow();
        assertThat(tor.name()).isEqualTo("Toronto Maple Leafs");
        assertThat(tor.shortName()).isEqualTo("Maple Leafs");
        assertThat(tor.location()).isEqualTo("Toronto");
        assertThat(tor.providerTeamId()).isEqualTo("TOR");
        assertThat(tor.logoUrl()).isEqualTo("https://a.espncdn.com/i/teamlogos/nhl/500/tor.png");
        assertThat(teams)
                .extracting(Team::id)
                .contains("nhl:la", "nhl:sj", "nhl:nj", "nhl:tb", "nhl:utah");
    }

    @Test
    void canonicalIdsMatchEspnAbbreviations() {
        Set<String> ours =
                NhlProvider.teamsFromStandings(Fixtures.json("nhl/standings-now")).stream()
                        .map(Team::id)
                        .collect(Collectors.toSet());
        assertThat(ours).isEqualTo(espnIds("espn/nhl/teams", "nhl"));
    }

    @Test
    void emptyStandingsFallBackToSeed(WireMockRuntimeInfo wm) {
        wm.getWireMock().register(get("/standings/now").willReturn(okJson("{\"standings\":[]}")));
        assertThat(provider(wm).fetchTeams(TestLeagues.get("nhl"))).hasSize(32);
    }

    @Test
    void upstreamFailureFallsBackToSeed(WireMockRuntimeInfo wm) {
        wm.getWireMock().register(get("/standings/now").willReturn(aResponse().withStatus(503)));
        assertThat(provider(wm).fetchTeams(TestLeagues.get("nhl"))).hasSize(32);
    }

    static Set<String> espnIds(String fixture, String league) {
        JsonNode teams =
                Fixtures.json(fixture).path("sports").path(0).path("leagues").path(0).path("teams");
        return StreamSupport.stream(teams.spliterator(), false)
                .map(t -> league + ":" + t.path("team").path("abbreviation").asText().toLowerCase())
                .collect(Collectors.toSet());
    }
}
