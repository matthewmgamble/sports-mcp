package ca.hex42labs.sports.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class LeagueRegistryTest {

    @Test
    void defaultsEnableTheNineMvpLeagues() {
        var reg = LeagueRegistry.standard(List.of());
        assertThat(reg.enabledIds())
                .containsExactly(
                        "nfl",
                        "nhl",
                        "nba",
                        "mlb",
                        "ncaaf",
                        "ncaab",
                        "epl",
                        "laliga",
                        "bundesliga");
        assertThat(reg.find("fifa")).isEmpty();
    }

    @Test
    void overrideEnablesExactlyTheListedLeagues() {
        var reg = LeagueRegistry.standard(List.of("nhl", "fifa"));
        assertThat(reg.enabledIds()).containsExactly("nhl", "fifa");
        assertThat(reg.find("nfl")).isEmpty();
    }

    @Test
    void unknownLeagueFailsFast() {
        assertThatThrownBy(() -> LeagueRegistry.standard(List.of("nlf")))
                .hasMessageContaining("nlf")
                .hasMessageContaining("nfl");
    }

    @Test
    void providersAreRoutedPerLeague() {
        var reg = LeagueRegistry.standard(List.of());
        assertThat(reg.find("nhl").orElseThrow().provider()).isEqualTo("nhl");
        assertThat(reg.find("mlb").orElseThrow().provider()).isEqualTo("mlb");
        assertThat(reg.find("epl").orElseThrow().provider()).isEqualTo("espn");
    }
}
