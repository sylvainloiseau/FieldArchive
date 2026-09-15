package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.List;

public class RdfEntitySummaryDto {
    public String entityKey;
    public String iri;
    public String label;
    public String source;     // internal/external
    public boolean editable;
    public String creationDate;
    public String modificationDate;
    // Of the type(s) requested by the caller of /rdf/entities (expanded with subtypes when
    // includeSubtypes=true), the ones this entity actually declares via rdf:type. Lets callers
    // group results by the entity's own type rather than by the (possibly broader) requested range.
    public List<String> matchedTypes;

}