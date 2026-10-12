package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.Team;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Result of one provider fetch: the games plus every team they reference. */
public record ProviderBatch(
        String league, LocalDate bucket, List<Game> games, List<Team> teams, Instant fetchedAt) {

    public ProviderBatch {
        games = List.copyOf(games);
        teams = List.copyOf(teams);
    }
}
