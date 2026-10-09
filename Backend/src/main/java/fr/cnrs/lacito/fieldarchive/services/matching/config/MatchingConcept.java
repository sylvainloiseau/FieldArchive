package fr.cnrs.lacito.fieldarchive.services.matching.config;

import java.util.List;
import java.util.Map;

/** A compared concept of a type group (runtime form: full IRIs, defaults applied). */
public record MatchingConcept(String key,
                              List<String> predicates,
                              ComparatorKind comparator,
                              double weight,
                              boolean blocking,
                              Map<String, Object> options) {

    public double numberOption(String name, double defaultValue) {
        Object v = options.get(name);
        return v instanceof Number n ? n.doubleValue() : defaultValue;
    }

    public boolean booleanOption(String name, boolean defaultValue) {
        Object v = options.get(name);
        return v instanceof Boolean b ? b : defaultValue;
    }
}
