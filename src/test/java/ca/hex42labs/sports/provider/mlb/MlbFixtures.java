package ca.hex42labs.sports.provider.mlb;

import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.testing.Fixtures;
import java.util.List;

public final class MlbFixtures {
    private MlbFixtures() {}

    public static List<Team> teams() {
        return MlbProvider.teams(Fixtures.json("mlb/teams"));
    }
}
