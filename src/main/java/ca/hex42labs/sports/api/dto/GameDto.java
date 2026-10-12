package ca.hex42labs.sports.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A game as clients see it. Absent fields are omitted rather than null: no score before the game,
 * no clock outside live play.
 *
 * @param startsAt UTC
 * @param localStart start in the request's timezone, ISO-8601 with offset
 * @param fetchedAt when this state was pulled from upstream
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record GameDto(
        String id,
        String league,
        String status,
        String statusDetail,
        Instant startsAt,
        String localStart,
        Integer period,
        String periodType,
        String clock,
        Side home,
        Side away,
        String venue,
        List<String> broadcasts,
        Situation situation,
        String source,
        Instant fetchedAt) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Side(
            String id,
            String name,
            String abbreviation,
            String logoUrl,
            Integer score,
            Integer shootout) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Situation(
            String possessionTeamId, String downDistance, String fieldPosition, boolean redZone) {}
}
