package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import fr.cnrs.lacito.fieldarchive.services.matching.config.ComparatorKind;

import java.util.EnumMap;
import java.util.Map;

/** The comparator implementation of each {@link ComparatorKind}. */
public final class ComparatorRegistry {

    private final Map<ComparatorKind, ValueComparator> comparators = new EnumMap<>(ComparatorKind.class);

    public ComparatorRegistry() {
        comparators.put(ComparatorKind.AUTO, new AutoComparator());
        comparators.put(ComparatorKind.STRING, new StringComparator());
        comparators.put(ComparatorKind.NAME, new NameComparator());
        comparators.put(ComparatorKind.TEXT, new TextComparator());
        comparators.put(ComparatorKind.EXACT, new ExactComparator());
        comparators.put(ComparatorKind.DATE, new DateComparator());
        comparators.put(ComparatorKind.NUMBER, new NumberComparator());
        comparators.put(ComparatorKind.LINKED_ENTITY, new LinkedEntityComparator());
    }

    public ValueComparator of(ComparatorKind kind) {
        return comparators.get(kind);
    }
}
