package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.util.OptionalDouble;

/**
 * Two dates precise to the day and equal → 1; intervals overlapping → {@code partialOverlapScore}
 * (0.85 by default, the notebook's value); disjoint → 0.
 */
public class DateComparator implements ValueComparator {

    public static final double DEFAULT_PARTIAL_OVERLAP_SCORE = 0.85;

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        DateInterval da = a.date(), db = b.date();
        if (da == null || db == null) return OptionalDouble.empty();
        if (!da.overlaps(db)) return OptionalDouble.of(0.0);
        if (da.dayPrecise() && db.dayPrecise() && da.start().equals(db.start())) return OptionalDouble.of(1.0);
        double partial = ctx.concept() != null
                ? ctx.concept().numberOption("partialOverlapScore", DEFAULT_PARTIAL_OVERLAP_SCORE)
                : DEFAULT_PARTIAL_OVERLAP_SCORE;
        return OptionalDouble.of(partial);
    }
}
