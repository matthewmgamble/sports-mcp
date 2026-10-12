package ca.hex42labs.sports.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** JSON-encoded TEXT columns (aliases, broadcasts, situation). */
final class JsonColumns {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final ObjectMapper json;

    JsonColumns(ObjectMapper json) {
        this.json = json;
    }

    String write(Object value) {
        if (value == null || value instanceof List<?> l && l.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> strings(String column) {
        return read(column, STRINGS, List.of());
    }

    <T> T read(String column, Class<T> type) {
        if (column == null) {
            return null;
        }
        try {
            return json.readValue(column, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON column: " + column, e);
        }
    }

    private <T> T read(String column, TypeReference<T> type, T fallback) {
        if (column == null) {
            return fallback;
        }
        try {
            return json.readValue(column, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt JSON column: " + column, e);
        }
    }
}
