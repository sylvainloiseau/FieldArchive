package com.uspn.rdf_back.dtos;

public class OntologyLabelDto {
    public String url;
    public String label;
    public String graphIri;
    public boolean builtIn;

    public OntologyLabelDto() {
    }

    public OntologyLabelDto(String url, String label) {
        this(url, label, null, false);
    }

    public OntologyLabelDto(String url, String label, String graphIri, boolean builtIn) {
        this.url = url;
        this.label = label;
        this.graphIri = graphIri;
        this.builtIn = builtIn;
    }
}