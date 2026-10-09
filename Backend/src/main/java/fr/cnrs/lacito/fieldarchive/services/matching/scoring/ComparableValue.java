package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

/**
 * A value ready to be compared: an RDF value resolved according to the concept's comparator
 * (a linked entity replaced by its text or its date, a literal parsed…).
 */
public record ComparableValue(Kind kind, String text, DateInterval date, Double number, String iri, String display) {

    public enum Kind { TEXT, DATE, NUMBER, ENTITY }

    public static ComparableValue text(String text) {
        return new ComparableValue(Kind.TEXT, text, null, null, null, text);
    }

    public static ComparableValue date(DateInterval date, String display) {
        return new ComparableValue(Kind.DATE, null, date, null, null, display);
    }

    public static ComparableValue number(double number, String display) {
        return new ComparableValue(Kind.NUMBER, null, null, number, null, display);
    }

    /** A linked entity: its IRI and its text (label), either of which may be null. */
    public static ComparableValue entity(String iri, String text) {
        return new ComparableValue(Kind.ENTITY, text, null, null, iri, text != null ? text : iri);
    }
}
