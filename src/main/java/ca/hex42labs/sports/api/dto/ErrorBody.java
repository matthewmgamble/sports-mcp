package ca.hex42labs.sports.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ErrorBody(Detail error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Detail(String code, String message, Map<String, Object> details) {}
}
