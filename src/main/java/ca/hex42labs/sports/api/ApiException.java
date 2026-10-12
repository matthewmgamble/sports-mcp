package ca.hex42labs.sports.api;

import ca.hex42labs.sports.api.dto.ErrorBody;
import java.util.Map;

/**
 * A request the caller can fix. Messages are written for agents: they say what was wrong and what
 * to send instead.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static ApiException invalid(String message) {
        return new ApiException(400, "invalid_argument", message, null);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message, null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public ErrorBody body() {
        return new ErrorBody(new ErrorBody.Detail(code, getMessage(), details));
    }
}
