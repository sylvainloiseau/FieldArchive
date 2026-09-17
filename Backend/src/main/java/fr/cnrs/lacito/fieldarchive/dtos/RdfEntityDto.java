package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

public class RdfEntityDto {
    public String entityKey;   // Used by the Frontend
    public String iri;        // IRI

    //public String id;                 // Complete IRI
    public List<RdfTypeDto> types = new ArrayList<>();  // ex: ["ric:Person"]
    public String source;             // "internal" | "external"
    public boolean editable;          // true if it is an InternalDataSource
    public List<RdfPropertyDto> properties = new ArrayList<>();
}
