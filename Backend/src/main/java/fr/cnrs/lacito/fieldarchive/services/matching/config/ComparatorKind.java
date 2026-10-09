package fr.cnrs.lacito.fieldarchive.services.matching.config;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/** The comparators a concept may use, with the options each one accepts. */
public enum ComparatorKind {
    AUTO("auto", Map.of()),
    STRING("string", Map.of()),
    NAME("name", Map.of()),
    TEXT("text", Map.of()),
    EXACT("exact", Map.of("caseSensitive", OptionType.BOOLEAN)),
    DATE("date", Map.of("partialOverlapScore", OptionType.UNIT_INTERVAL)),
    NUMBER("number", Map.of("tolerance", OptionType.NON_NEGATIVE)),
    LINKED_ENTITY("linkedEntity", Map.of("labelFactor", OptionType.UNIT_INTERVAL));

    /** How an option value is validated. */
    public enum OptionType { BOOLEAN, UNIT_INTERVAL, NON_NEGATIVE }

    private final String jsonName;
    private final Map<String, OptionType> options;

    ComparatorKind(String jsonName, Map<String, OptionType> options) {
        this.jsonName = jsonName;
        this.options = options;
    }

    public String jsonName() {
        return jsonName;
    }

    public Map<String, OptionType> options() {
        return options;
    }

    public static Optional<ComparatorKind> fromJson(String name) {
        return Arrays.stream(values()).filter(k -> k.jsonName.equals(name)).findFirst();
    }
}
