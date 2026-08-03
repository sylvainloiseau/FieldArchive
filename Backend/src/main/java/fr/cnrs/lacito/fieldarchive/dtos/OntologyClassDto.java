package fr.cnrs.lacito.fieldarchive.dtos;

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

    public String getIri() {
        return iri;
    }

    public String getLocalName() {
        return localName;
    }

    public String getLabel() {
        return label;
    }
}
