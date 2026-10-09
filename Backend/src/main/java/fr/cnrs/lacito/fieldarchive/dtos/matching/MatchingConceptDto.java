package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One compared concept of a type group: the predicates expressing it, and how to compare them. */
public class MatchingConceptDto {
    public List<String> predicates = new ArrayList<>();
    /** auto | string | name | text | exact | date | number | linkedEntity */
    public String comparator = "auto";
    public Double weight = 1.0;
    public Boolean blocking = false;
    /** Comparator options: partialOverlapScore, tolerance, labelFactor, caseSensitive. */
    public Map<String, Object> options = new LinkedHashMap<>();
}
