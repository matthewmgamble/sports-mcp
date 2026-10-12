package ca.hex42labs.sports.provider.nhl;

import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.testing.Fixtures;
import java.util.List;

public final class NhlFixtures {
    private NhlFixtures() {}

    public static List<Team> teams() {
        return NhlProvider.teamsFromStandings(Fixtures.json("nhl/standings-now"));
    }
}
