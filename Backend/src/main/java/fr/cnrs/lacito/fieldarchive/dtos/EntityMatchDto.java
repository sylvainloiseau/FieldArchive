package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

/** An entity found by a label search (search_entities tool, CLARIFY candidates). */
public class EntityMatchDto {
    public String iri;
    public String label;
    public List<String> types = new ArrayList<>();
    public String source;               // internal | external
    public String datasourceShortName;
    public double score;
}
