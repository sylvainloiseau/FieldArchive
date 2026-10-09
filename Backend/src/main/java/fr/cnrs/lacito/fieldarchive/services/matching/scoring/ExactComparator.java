package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.utils.StringSimilarity;

import java.util.OptionalDouble;

/** 1 when the values are equal (normalised unless {@code caseSensitive}), else 0. */
public class ExactComparator implements ValueComparator {

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        if (a.text() == null || b.text() == null) return OptionalDouble.empty();
        boolean caseSensitive = ctx.concept() != null && ctx.concept().booleanOption("caseSensitive", false);
        String va = caseSensitive ? a.text().trim() : StringSimilarity.normalize(a.text());
        String vb = caseSensitive ? b.text().trim() : StringSimilarity.normalize(b.text());
        if (va.isEmpty() || vb.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(va.equals(vb) ? 1.0 : 0.0);
    }
}
