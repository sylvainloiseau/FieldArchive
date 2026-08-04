package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

public class RdfPropertyDto {
    public String predicate;           // ex: "ric:hasName" ou IRI complet
    public String kind;        // "literal" | "iri" | "other"
    public List<RdfValueDto> values = new ArrayList<>();
}