package ca.hex42labs.sports.provider.espn;

import ca.hex42labs.sports.domain.Team;
import ca.hex42labs.sports.testing.Fixtures;
import ca.hex42labs.sports.testing.TestLeagues;
import java.util.List;

/** Real ESPN team lists from fixtures, for tests outside this package. */
public final class EspnFixtures {
    private EspnFixtures() {}

    public static List<Team> teams(String league) {
        return EspnMapper.teams(
                TestLeagues.get(league), Fixtures.json("espn/" + league + "/teams"));
    }
}
