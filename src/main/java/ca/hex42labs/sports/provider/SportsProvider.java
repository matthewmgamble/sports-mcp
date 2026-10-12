package ca.hex42labs.sports.provider;

import ca.hex42labs.sports.domain.League;
import ca.hex42labs.sports.domain.Team;
import java.time.LocalDate;
import java.util.List;

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
}
