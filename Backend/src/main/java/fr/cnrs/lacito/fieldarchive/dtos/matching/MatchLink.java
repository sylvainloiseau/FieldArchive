package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * A link between two entities of different data sources, as stored in
 * {@code data_matching.json}. {@code a} and {@code b} follow the canonical order of their
 * data-source pair (shortNames sorted).
 */
public class MatchLink {
    public String id;
    public EntityNodeDto a;
    public EntityNodeDto b;
    public MatchStatus status;
    public LinkOrigin origin;
    public String typeGroupId;
    public String typeGroupLabel;
    public Double score;
    public Double comparedWeight;
    public List<ConceptEvidenceDto> evidence = new ArrayList<>();
    /** INFERRED only: the steps of the path joining the two entities ("identical:<iri>" or link ids). */
    public List<String> inferredFrom = new ArrayList<>();
    public String proposedAt;
    public String decidedAt;
    public String note;
}
