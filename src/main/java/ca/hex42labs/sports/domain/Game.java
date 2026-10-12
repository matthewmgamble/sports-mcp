package ca.hex42labs.sports.domain;

import java.time.Instant;
import java.util.List;

/**
 * One game with its latest known state. Scores are null unless {@link GameStatus#hasScore()}.
 *
 * @param id canonical id, e.g. {@code nfl:401872994}
 * @param seasonType {@code preseason}, {@code regular}, {@code postseason} (or a provider slug)
 * @param statusDetail provider's short human text: "Q3 5:42", "Final/OT", "10/12 - 8:15 PM EDT"
 * @param fetchedAt when we pulled this state from the provider
 * @param providerUpdatedAt the provider's own timestamp for this state, if it gives one
 * @param round postseason only: the round or game, as the provider names it ("ALCS Game 4", "1st
 *     Round")
 * @param series postseason only: the series standing ("TOR leads 2-1", "Series tied 2-2")
 * @param startTimeTbd the date is set but the start time isn't yet ({@link #startsAt} is a
 *     placeholder time on that date)
 */
public record Game(
        String id,
        String league,
        String provider,
        String providerEventId,
        String season,
        String seasonType,
        String homeTeamId,
        String awayTeamId,
        Instant startsAt,
        GameStatus status,
        String statusDetail,
        Integer period,
        PeriodType periodType,
        String clock,
        Integer homeScore,
        Integer awayScore,
        Integer homeShootout,
        Integer awayShootout,
        String venue,
        List<String> broadcasts,
        Situation situation,
        Instant fetchedAt,
        Instant providerUpdatedAt,
        String round,
        String series,
        boolean startTimeTbd) {

    public Game {
        broadcasts = broadcasts == null ? List.of() : List.copyOf(broadcasts);
    }

    /** A regular-season game with a known start time. */
    public Game(
            String id,
            String league,
            String provider,
            String providerEventId,
            String season,
            String seasonType,
            String homeTeamId,
            String awayTeamId,
            Instant startsAt,
            GameStatus status,
            String statusDetail,
            Integer period,
            PeriodType periodType,
            String clock,
            Integer homeScore,
            Integer awayScore,
            Integer homeShootout,
            Integer awayShootout,
            String venue,
            List<String> broadcasts,
            Situation situation,
            Instant fetchedAt,
            Instant providerUpdatedAt) {
        this(
                id,
                league,
                provider,
                providerEventId,
                season,
                seasonType,
                homeTeamId,
                awayTeamId,
                startsAt,
                status,
                statusDetail,
                period,
                periodType,
                clock,
                homeScore,
                awayScore,
                homeShootout,
                awayShootout,
                venue,
                broadcasts,
                situation,
                fetchedAt,
                providerUpdatedAt,
                null,
                null,
                false);
    }

    /**
     * Live football down-and-distance.
     *
     * @param possessionTeamId canonical id of the team with the ball
     * @param downDistance "2nd & 13"
     * @param fieldPosition "DET 31"
     */
    public record Situation(
            String possessionTeamId, String downDistance, String fieldPosition, boolean redZone) {}
}
