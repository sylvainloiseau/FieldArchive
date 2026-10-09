package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.util.OptionalDouble;

/** 1 when the two numbers differ by at most {@code tolerance} (0 by default), else 0. */
public class NumberComparator implements ValueComparator {

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        if (a.number() == null || b.number() == null) return OptionalDouble.empty();
        double tolerance = ctx.concept() != null ? ctx.concept().numberOption("tolerance", 0) : 0;
        return OptionalDouble.of(Math.abs(a.number() - b.number()) <= tolerance ? 1.0 : 0.0);
    }
}
