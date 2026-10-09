package fr.cnrs.lacito.fieldarchive.dtos.matching;

/** How one concept contributed to a pair's score: the best pair of values and their score. */
public class ConceptEvidenceDto {
    public String concept;
    public String comparator;
    public double weight;
    public double score;
    public String valueA;
    public String valueB;
}
