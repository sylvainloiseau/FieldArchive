package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.util.OptionalDouble;

/**
 * Two linked entities: 1 when they are the same IRI or already known to be the same entity
 * (same cluster); otherwise the name similarity of their labels, damped by {@code labelFactor}
 * (0.9 by default) since a similar label is weaker evidence than an identity.
 */
public class LinkedEntityComparator implements ValueComparator {

    public static final double DEFAULT_LABEL_FACTOR = 0.9;

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        if (a.iri() != null && b.iri() != null) {
            boolean same = a.iri().equals(b.iri())
                    || ctx.sameEntity() != null && ctx.sameEntity().test(a.iri(), b.iri());
            if (same) return OptionalDouble.of(1.0);
        }
        if (a.text() == null || b.text() == null) return OptionalDouble.empty();
        OptionalDouble similarity = NameComparator.similarity(a.text(), b.text());
        if (similarity.isEmpty()) return similarity;
        double factor = ctx.concept() != null ? ctx.concept().numberOption("labelFactor", DEFAULT_LABEL_FACTOR) : DEFAULT_LABEL_FACTOR;
        return OptionalDouble.of(factor * similarity.getAsDouble());
    }
}
