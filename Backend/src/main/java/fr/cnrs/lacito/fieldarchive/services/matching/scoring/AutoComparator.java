package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.util.OptionalDouble;

/** Chooses a comparator from the kinds of the two values; values of different kinds are not comparable. */
public class AutoComparator implements ValueComparator {

    private final ValueComparator string = new StringComparator();
    private final ValueComparator date = new DateComparator();
    private final ValueComparator number = new NumberComparator();
    private final ValueComparator linked = new LinkedEntityComparator();

    @Override
    public OptionalDouble compare(ComparableValue a, ComparableValue b, ComparisonContext ctx) {
        ComparableValue.Kind ka = a.kind(), kb = b.kind();
        if (ka == ComparableValue.Kind.ENTITY || kb == ComparableValue.Kind.ENTITY) {
            boolean other = ka == ComparableValue.Kind.ENTITY ? kb == ComparableValue.Kind.ENTITY || kb == ComparableValue.Kind.TEXT
                    : ka == ComparableValue.Kind.TEXT;
            return other ? linked.compare(a, b, ctx) : OptionalDouble.empty();
        }
        if (ka != kb) return OptionalDouble.empty();
        return switch (ka) {
            case TEXT -> string.compare(a, b, ctx);
            case DATE -> date.compare(a, b, ctx);
            case NUMBER -> number.compare(a, b, ctx);
            case ENTITY -> linked.compare(a, b, ctx);
        };
    }
}
