package com.uspn.rdf_back.dtos;

public class OntologyLabelDto {
    public String url;
    public String label;

    public OntologyLabelDto() {
    }

    public OntologyLabelDto(String url, String label) {
        this.url = url;
        this.label = label;
    }
}