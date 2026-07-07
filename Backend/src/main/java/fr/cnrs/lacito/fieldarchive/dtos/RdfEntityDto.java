package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

public class RdfEntityDto {
    public String entityKey;   // utilisé par le frontend
    public String iri;        // IRI

    //public String id;                 // IRI complet
    public List<String> types = new ArrayList<>();  // ex: ["ric:Person"]
    public String source;             // "internal" | "external"
    public boolean editable;          // true si interne
    public List<RdfPropertyDto> properties = new ArrayList<>();
}