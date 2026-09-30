package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

/** Sent with a SPARQL run that comes from a natural-language question, so it can be recorded in the history. */
public class NlHistoryContext {
    public String question;
    public List<NlClarification> clarifications = new ArrayList<>();
    public String generatedSparql;
    public String targetEntityIri;
    public List<String> createdEntityIris = new ArrayList<>();
    public String provider;
    public String model;
}
