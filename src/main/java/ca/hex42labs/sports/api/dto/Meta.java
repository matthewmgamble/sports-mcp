package ca.hex42labs.sports.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Provenance and freshness for every response.
 *
 * @param asOf when the oldest returned data was fetched from upstream
 * @param isStale some data is older than it should be (upstream slow or down); see staleReason
 * @param truncated more results matched than {@code limit} allowed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Meta(
        List<String> sources,
        Instant asOf,
        @JsonProperty("isStale") boolean isStale,
        String staleReason,
        String attribution,
        String timezone,
        Integer count,
        Boolean truncated) {}
