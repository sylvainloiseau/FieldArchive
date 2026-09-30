package fr.cnrs.lacito.fieldarchive.dtos;

/** An answer the user gave to a CLARIFY round (which entity, or which encoding). */
public class NlClarification {
    public String kind;       // ENTITY | ENCODING
    public String entityIri;  // for ENTITY
    public String optionId;   // for ENCODING
    public String statement;  // human-readable decision passed back to the agent
}
