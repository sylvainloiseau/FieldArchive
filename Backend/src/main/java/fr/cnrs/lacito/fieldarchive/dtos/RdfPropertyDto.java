package fr.cnrs.lacito.fieldarchive.dtos;

public class RdfPropertyDto {
    public String predicate;   // ex: "ric:hasName" ou IRI complet
    public String kind;        // "literal" | "iri"
    public String value;       // valeur littérale ou IRI/curie
    public String name ;       // when the entity is of type IRI, return its name also.
    public String datatype;    // ex: "xsd:string" (optionnel pour literal)
    public String lang;        // ex: "fr" (optionnel pour literal)
}