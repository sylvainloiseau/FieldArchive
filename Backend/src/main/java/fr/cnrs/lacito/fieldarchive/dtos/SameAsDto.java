package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

/** An IRI declared the same entity by the data matching ({@code owl:sameAs} graph). */
public class SameAsDto {
    public String iri;
    public String label;
    /** Data sources in which that IRI has statements. */
    public List<String> datasourceShortNames = new ArrayList<>();
}
