package fr.cnrs.lacito.fieldarchive.dtos;

public class OntologyRangeDto {
    private String uri;
    private String localName;

    public OntologyRangeDto() {
    }

    public OntologyRangeDto(String uri, String localName) {
        this.uri = uri;
        this.localName = localName;
    }

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getLocalName() { return localName; }
    public void setLocalName(String localName) { this.localName = localName; }
}