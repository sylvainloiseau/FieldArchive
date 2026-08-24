package fr.cnrs.lacito.fieldarchive.dtos;

public class RdfValueDto {
    public String kind;        // "literal" | "iri" | "other"
    public String value;       // valeur littérale ou IRI/curie
    public String name;        // when kind == "iri", the resolved entity name
    public String datatype;    // ex: "xsd:string" (optionnel pour literal)
    public String lang;        // ex: "fr" (optionnel pour literal)

    // NEW
    public String source;            // "internal" | "external"
    public String datasourceShortName; // e.g. "internal", "test", null if unresolved
}