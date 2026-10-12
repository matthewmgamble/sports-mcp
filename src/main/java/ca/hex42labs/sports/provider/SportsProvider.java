package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.Game;
import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * A source of sports data for one or more leagues. Implementations translate provider payloads into
 * the domain model and never touch the database.
 */
public interface SportsProvider {

    /** {@code espn}, {@code nhl} or {@code mlb}. Matches {@link League#provider()}. */
    String id();

    /** The league's full team list. */
    List<Team> fetchTeams(League league) throws ProviderException;

    /**
     * Every game in one provider date bucket (ESPN: a US-Eastern day), with the teams they
     * reference. One call refreshes all games on that date, so it doubles as the live batch.
     */
    ProviderBatch fetchScoreboard(League league, LocalDate bucket) throws ProviderException;

    /**
     * Every game in the buckets {@code from..to} inclusive, using whatever request pattern suits
     * the provider (ESPN: one call per day; NHL: weekly pages; MLB: one range call). Partial
     * failures are reported in the result.
     *
     * @throws ProviderException only if nothing at all could be fetched
     */
    ScheduleResult fetchSchedule(League league, LocalDate from, LocalDate to)
            throws ProviderException;

    /**
     * A fresher view of one live game than its scoreboard gives, if the provider has one (MLB's
     * per-game linescore; the schedule's hydrated linescore is CDN-cached and lags). Empty when the
     * scoreboard is already as fresh as it gets.
     */
    default Optional<Game> refreshGame(League league, Game game) throws ProviderException {
        return Optional.empty();
    }

    /**
     * One team's current-season schedule, past and future. Reaches beyond the background sync
     * horizon, so "next 5 games" works for weekly sports.
     */
    ScheduleResult fetchTeamSchedule(League league, Team team) throws ProviderException;

    /** Whether {@link #refreshGame} is worth calling for live games. */
    default boolean hasPerGameFeed() {
        return false;
    }
}
