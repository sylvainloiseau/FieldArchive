package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/** The best score over every combination of the values of two multi-valued concepts. */
public final class MultiValueComparison {

    /** At most this many values per side are compared, to bound the cost. */
    public static final int MAX_VALUES_PER_SIDE = 20;

    public record Best(double score, ComparableValue valueA, ComparableValue valueB) {}

    private MultiValueComparison() {}

    public static Optional<Best> bestOver(List<ComparableValue> valuesA, List<ComparableValue> valuesB,
                                          ValueComparator comparator, ComparisonContext ctx) {
        Best best = null;
        List<ComparableValue> la = valuesA.subList(0, Math.min(valuesA.size(), MAX_VALUES_PER_SIDE));
        List<ComparableValue> lb = valuesB.subList(0, Math.min(valuesB.size(), MAX_VALUES_PER_SIDE));
        for (ComparableValue a : la) {
            for (ComparableValue b : lb) {
                OptionalDouble s = comparator.compare(a, b, ctx);
                if (s.isPresent() && (best == null || s.getAsDouble() > best.score())) {
                    best = new Best(s.getAsDouble(), a, b);
                    if (best.score() >= 1.0) return Optional.of(best);
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
