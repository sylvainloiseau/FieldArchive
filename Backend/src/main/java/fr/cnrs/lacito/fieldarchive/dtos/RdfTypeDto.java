package fr.cnrs.lacito.fieldarchive.dtos;

public class RdfTypeDto {
    public String iri;
    public String source;              // "internal" | "external"
    public String datasourceShortName; // graph short name, or "internal"
}