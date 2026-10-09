package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.util.OptionalDouble;

/** Compares two values of a concept. */
public interface ValueComparator {

    /** A score in [0, 1], or empty when the two values cannot be compared. */
    OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx);
}
