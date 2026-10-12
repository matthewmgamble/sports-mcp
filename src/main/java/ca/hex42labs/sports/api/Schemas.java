package ca.hex42labs.sports.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response schemas from {@code api/schemas.json}, defined once and used three ways: inlined as MCP
 * tool output schemas (clients don't reliably resolve {@code $ref}), as OpenAPI components, and by
 * contract tests.
 */
public final class Schemas {

    private static final String DEFS_REF = "#/$defs/";
    private static final String OPENAPI_REF = "#/components/schemas/";

    private final Map<String, Object> defs;

    private Schemas(Map<String, Object> defs) {
        this.defs = defs;
    }

    @SuppressWarnings("unchecked")
    public static Schemas bundled() {
        Map<String, Object> root = load("/api/schemas.json");
        return new Schemas((Map<String, Object>) root.get("$defs"));
    }

    public static Map<String, Object> load(String resource) {
        try (InputStream in = Schemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            return new ObjectMapper().readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The full response schema for a tool's {@code output} spec ({@code list:Game}, {@code
     * one:Game}): an envelope with {@code data} and {@code meta}, all refs inlined.
     */
    public Map<String, Object> envelope(String output) {
        String[] parts = output.split(":", 2);
        Map<String, Object> item = Map.of("$ref", DEFS_REF + parts[1]);
        Object data =
                switch (parts[0]) {
                    case "list" -> Map.of("type", "array", "items", item);
                    case "one" -> item;
                    default -> throw new IllegalArgumentException("Bad output spec " + output);
                };
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "object");
        envelope.put("additionalProperties", false);
        envelope.put("required", List.of("data", "meta"));
        envelope.put("properties", Map.of("data", data, "meta", Map.of("$ref", DEFS_REF + "Meta")));
        return inline(envelope);
    }

    /** Same envelope with OpenAPI-style refs, for path responses. */
    public Map<String, Object> envelopeRef(String output) {
        String[] parts = output.split(":", 2);
        Map<String, Object> item = Map.of("$ref", OPENAPI_REF + parts[1]);
        Object data = parts[0].equals("list") ? Map.of("type", "array", "items", item) : item;
        return Map.of(
                "type", "object",
                "required", List.of("data", "meta"),
                "properties", Map.of("data", data, "meta", Map.of("$ref", OPENAPI_REF + "Meta")));
    }

    /** All definitions with refs rewritten for {@code components.schemas}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> openApiComponents() {
        return (Map<String, Object>) rewrite(defs);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> inline(Map<String, Object> schema) {
        return (Map<String, Object>) inlineValue(schema);
    }

    @SuppressWarnings("unchecked")
    private Object inlineValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Object ref = m.get("$ref");
            if (ref instanceof String s && s.startsWith(DEFS_REF)) {
                Object target = defs.get(s.substring(DEFS_REF.length()));
                if (target == null) {
                    throw new IllegalStateException("Unknown schema " + s);
                }
                return inlineValue(target);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, val) -> out.put(k, inlineValue(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(inlineValue(x)));
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Object rewrite(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m)
                    .forEach(
                            (k, val) ->
                                    out.put(
                                            k,
                                            k.equals("$ref")
                                                            && val instanceof String s
                                                            && s.startsWith(DEFS_REF)
                                                    ? OPENAPI_REF + s.substring(DEFS_REF.length())
                                                    : rewrite(val)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(rewrite(x)));
            return out;
        }
        return v;
    }
}
