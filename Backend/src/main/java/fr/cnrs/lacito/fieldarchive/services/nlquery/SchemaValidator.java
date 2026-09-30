package fr.cnrs.lacito.fieldarchive.services.nlquery;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Validates JSON against the small JSON Schema subset used by {@code nlquery/tools.json} and
 * {@code nlquery/output-schema.json}: type, enum, properties, required, additionalProperties:
 * false, items. Applied to every model answer and tool call whatever the provider: a safety net
 * behind {@code strict} for Claude and ChatGPT, the only check for the local model.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /** Empty when valid, else the list of problems ("$.encodingOptions[0].basis: …"). */
    public static List<String> validate(JsonNode value, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        check(value, schema, "$", errors);
        return errors;
    }

    private static void check(JsonNode v, JsonNode schema, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode()) return;
        String type = schema.path("type").asText("");
        if (v == null || v.isMissingNode() || v.isNull()) {
            errors.add(path + ": missing");
            return;
        }
        switch (type) {
            case "object" -> {
                if (!v.isObject()) { errors.add(path + ": must be an object"); return; }
                JsonNode props = schema.path("properties");
                for (JsonNode r : schema.path("required")) {
                    if (!v.has(r.asText())) errors.add(path + "." + r.asText() + ": missing");
                }
                if (schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()) {
                    Iterator<String> names = v.fieldNames();
                    while (names.hasNext()) {
                        String n = names.next();
                        if (!props.has(n)) errors.add(path + "." + n + ": unexpected property");
                    }
                }
                Iterator<String> names = props.fieldNames();
                while (names.hasNext()) {
                    String n = names.next();
                    if (v.has(n)) check(v.get(n), props.get(n), path + "." + n, errors);
                }
            }
            case "array" -> {
                if (!v.isArray()) { errors.add(path + ": must be an array"); return; }
                for (int i = 0; i < v.size(); i++) check(v.get(i), schema.path("items"), path + "[" + i + "]", errors);
            }
            case "string" -> {
                if (!v.isTextual()) { errors.add(path + ": must be a string"); return; }
                if (schema.has("enum")) {
                    boolean ok = false;
                    for (JsonNode e : schema.get("enum")) if (e.asText().equals(v.asText())) ok = true;
                    if (!ok) errors.add(path + ": must be one of " + schema.get("enum"));
                }
            }
            case "integer" -> { if (!v.isIntegralNumber()) errors.add(path + ": must be an integer"); }
            case "number" -> { if (!v.isNumber()) errors.add(path + ": must be a number"); }
            case "boolean" -> { if (!v.isBoolean()) errors.add(path + ": must be a boolean"); }
            default -> { /* no type constraint */ }
        }
    }
}
