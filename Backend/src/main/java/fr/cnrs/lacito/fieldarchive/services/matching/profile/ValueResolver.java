package fr.cnrs.lacito.fieldarchive.services.matching.profile;

import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConcept;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.ComparableValue;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.DateInterval;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.PartialDate;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.XSD;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the RDF values of a concept into {@link ComparableValue}s according to its comparator:
 * a linked entity becomes its text for the string comparators and its date for the date
 * comparator, so that {@code rico:name "Jean"} can be compared with
 * {@code rico:hasOrHadName → rico:Name "Jean"}. Values that cannot be resolved are dropped.
 */
public final class ValueResolver {

    private static final Set<IRI> DATE_TYPES = Set.of(XSD.DATE, XSD.DATETIME, XSD.GYEAR, XSD.GYEARMONTH, XSD.DATETIMESTAMP);
    private static final Set<IRI> NUMBER_TYPES = Set.of(XSD.INTEGER, XSD.INT, XSD.LONG, XSD.SHORT, XSD.BYTE,
            XSD.DECIMAL, XSD.DOUBLE, XSD.FLOAT, XSD.NON_NEGATIVE_INTEGER, XSD.POSITIVE_INTEGER,
            XSD.NON_POSITIVE_INTEGER, XSD.NEGATIVE_INTEGER, XSD.UNSIGNED_INT, XSD.UNSIGNED_LONG,
            XSD.UNSIGNED_SHORT, XSD.UNSIGNED_BYTE);

    private final LinkedValueLookup lookup;

    public ValueResolver(LinkedValueLookup lookup) {
        this.lookup = lookup;
    }

    public List<ComparableValue> resolve(EntityProfile profile, MatchingConcept concept) {
        return profile.resolved.computeIfAbsent(concept, c -> {
            List<ComparableValue> out = new ArrayList<>();
            for (Value v : profile.valuesOf(c)) resolve(v, c).ifPresent(out::add);
            return out;
        });
    }

    public Optional<ComparableValue> resolve(Value v, MatchingConcept concept) {
        return switch (concept.comparator()) {
            case STRING, NAME, TEXT, EXACT -> asText(v);
            case DATE -> asDate(v);
            case NUMBER -> v instanceof Literal l ? asNumber(l) : Optional.empty();
            case LINKED_ENTITY -> v instanceof IRI iri
                    ? Optional.of(ComparableValue.entity(iri.stringValue(), lookup.textOf(iri.stringValue()).orElse(null)))
                    : v instanceof Literal l ? Optional.of(ComparableValue.entity(null, l.getLabel())) : Optional.empty();
            case AUTO -> auto(v);
        };
    }

    private Optional<ComparableValue> auto(Value v) {
        if (v instanceof IRI iri) {
            return Optional.of(ComparableValue.entity(iri.stringValue(), lookup.textOf(iri.stringValue()).orElse(null)));
        }
        if (!(v instanceof Literal l)) return Optional.empty();
        if (DATE_TYPES.contains(l.getDatatype())) return asDate(l);
        if (NUMBER_TYPES.contains(l.getDatatype())) return asNumber(l);
        return Optional.of(ComparableValue.text(l.getLabel()));
    }

    private Optional<ComparableValue> asText(Value v) {
        if (v instanceof Literal l) return Optional.of(ComparableValue.text(l.getLabel()));
        if (v instanceof IRI iri) return lookup.textOf(iri.stringValue()).map(ComparableValue::text);
        return Optional.empty();
    }

    private Optional<ComparableValue> asDate(Value v) {
        if (v instanceof Literal l) {
            return PartialDate.parse(l.getLabel()).map(d -> ComparableValue.date(d, l.getLabel()));
        }
        if (v instanceof IRI iri) {
            Optional<DateInterval> d = lookup.dateOf(iri.stringValue());
            return d.map(x -> ComparableValue.date(x, lookup.textOf(iri.stringValue()).orElse(x.toString())));
        }
        return Optional.empty();
    }

    private Optional<ComparableValue> asNumber(Literal l) {
        try {
            return Optional.of(ComparableValue.number(Double.parseDouble(l.getLabel().trim()), l.getLabel()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
