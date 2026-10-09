package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A group of equivalent classes and the concepts (properties) used to compare its members. */
public class TypeGroupDto {
    public String id;
    public String groupLabel;
    public String note;
    public List<String> equivalentTypes = new ArrayList<>();
    /** Optional overrides of the defaults (null = inherit). */
    public Double threshold;
    public Double minimumComparedWeight;
    public List<String> requiredConcepts = new ArrayList<>();
    /** Concept name → concept, in comparison order. */
    public Map<String, MatchingConceptDto> properties = new LinkedHashMap<>();
}
