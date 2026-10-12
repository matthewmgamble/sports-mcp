package ca.hex42labs.sports.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamDto(
        String id,
        String league,
        String name,
        String shortName,
        String abbreviation,
        String location,
        String logoUrl) {}
