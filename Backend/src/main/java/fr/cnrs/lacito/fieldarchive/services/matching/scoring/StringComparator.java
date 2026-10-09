package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.utils.StringSimilarity;

import java.util.OptionalDouble;

/**
 * Indel ratio of the normalised texts (= {@code fuzz.ratio}/100 of the notebook). Long texts
 * (over {@value #LONG_TEXT} characters) are compared as bags of words instead.
 */
public class StringComparator implements ValueComparator {

    static final int LONG_TEXT = 200;

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        if (a.text() == null || b.text() == null) return OptionalDouble.empty();
        String na = StringSimilarity.normalize(a.text());
        String nb = StringSimilarity.normalize(b.text());
        if (na.isEmpty() || nb.isEmpty()) return OptionalDouble.empty();
        if (na.length() > LONG_TEXT || nb.length() > LONG_TEXT) return TextComparator.jaccard(a.text(), b.text());
        return OptionalDouble.of(StringSimilarity.indelRatio(na, nb));
    }
}
