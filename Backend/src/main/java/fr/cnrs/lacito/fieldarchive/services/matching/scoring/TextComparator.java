package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.utils.TextNormalizer;

import java.util.OptionalDouble;
import java.util.Set;

/** Jaccard similarity of the content words, for long descriptions. */
public class TextComparator implements ValueComparator {

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        if (a.text() == null || b.text() == null) return OptionalDouble.empty();
        return jaccard(a.text(), b.text());
    }

    static OptionalDouble jaccard(String a, String b) {
        Set<String> wa = TextNormalizer.contentWords(a);
        Set<String> wb = TextNormalizer.contentWords(b);
        if (wa.isEmpty() || wb.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(TextNormalizer.jaccard(wa, wb));
    }
}
