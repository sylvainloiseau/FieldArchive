package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.utils.StringSimilarity;

import java.util.OptionalDouble;

/** Best of the indel ratio and the token-sort ratio: "Dupont, Jean" = "Jean Dupont". */
public class NameComparator implements ValueComparator {

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        if (a.text() == null || b.text() == null) return OptionalDouble.empty();
        return similarity(a.text(), b.text());
    }

    static OptionalDouble similarity(String a, String b) {
        String na = StringSimilarity.normalize(a);
        String nb = StringSimilarity.normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return OptionalDouble.empty();
        return OptionalDouble.of(Math.max(StringSimilarity.indelRatio(na, nb), StringSimilarity.tokenSortRatio(a, b)));
    }
}
