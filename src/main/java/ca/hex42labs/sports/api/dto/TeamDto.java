package ca.hex42labs.sports.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A team. {@code color}, {@code record} and {@code standing} come only with one team's details
 * ({@code GET /teams/{team}}).
 *
 * @param color hex without '#'
 * @param record this part of the season: "3-2-1"
 * @param standing "4th in Atlantic Division"
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamDto(
        String id,
        String league,
        String name,
        String shortName,
        String abbreviation,
        String location,
        String logoUrl,
        String color,
        String record,
        String standing) {

    /** Without the season details (lists and searches). */
    public TeamDto(
            String id,
            String league,
            String name,
            String shortName,
            String abbreviation,
            String location,
            String logoUrl) {
        this(id, league, name, shortName, abbreviation, location, logoUrl, null, null, null);
    }
}
