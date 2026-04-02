package com.uspn.rdf_back.dtos;

public class OntologyClassDto {
    public String iri;
    public String localName;
    public String label;

    public OntologyClassDto() {
    }

    public OntologyClassDto(String iri, String localName, String label) {
        this.iri = iri;
        this.localName = localName;
        this.label = label;
    }

}
