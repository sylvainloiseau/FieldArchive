package fr.cnrs.lacito.fieldarchive.dtos;

public class SparqlRequest {
    public String query;
    public NlHistoryContext nl;   // optional: present only when the query comes from a question
}
