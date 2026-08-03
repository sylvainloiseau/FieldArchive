package fr.cnrs.lacito.fieldarchive.dtos;

public class OntologyPropertyDto {
    private String uri;
    private String localName;
    private String label;

    private PropertyKind kind;

    private String domainUri;        // classe de domaine (contexte)
    private String domainLocalName;

    private String rangeUri;         // pour ObjectProperty
    private String rangeLocalName;

    private DataTypeCategory dataTypeCategory; // pour DataProperty
    private String datatypeUri;                // ex: xsd:string
    private String language;                   // "*" si rdf:langString

    private CardinalityDto cardinality;

    // --- getters / setters ---
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getLocalName() { return localName; }
    public void setLocalName(String localName) { this.localName = localName; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public PropertyKind getKind() { return kind; }
    public void setKind(PropertyKind kind) { this.kind = kind; }
    public String getDomainUri() { return domainUri; }
    public void setDomainUri(String domainUri) { this.domainUri = domainUri; }
    public String getDomainLocalName() { return domainLocalName; }
    public void setDomainLocalName(String domainLocalName) { this.domainLocalName = domainLocalName; }
    public String getRangeUri() { return rangeUri; }
    public void setRangeUri(String rangeUri) { this.rangeUri = rangeUri; }
    public String getRangeLocalName() { return rangeLocalName; }
    public void setRangeLocalName(String rangeLocalName) { this.rangeLocalName = rangeLocalName; }
    public DataTypeCategory getDatatypeCategory() { return dataTypeCategory; }
    public void setDatatypeCategory(DataTypeCategory datatypeCategory) { this.dataTypeCategory = dataTypeCategory; }
    public String getDatatypeUri() { return datatypeUri; }
    public void setDatatypeUri(String datatypeUri) { this.datatypeUri = datatypeUri; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public CardinalityDto getCardinality() { return cardinality; }
    public void setCardinality(CardinalityDto cardinality) { this.cardinality = cardinality; }
}