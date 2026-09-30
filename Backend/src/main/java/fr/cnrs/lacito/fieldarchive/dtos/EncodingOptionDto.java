package fr.cnrs.lacito.fieldarchive.dtos;

/** One way of recording the requested information, proposed by the agent in an ENCODING clarify. */
public class EncodingOptionDto {
    public String id;
    public String label;
    public String description;
    public String basis;         // project data | configuration | RiC-O
    public String exampleTriple;
    public String statement;     // copied into NlClarification.statement when picked
}
