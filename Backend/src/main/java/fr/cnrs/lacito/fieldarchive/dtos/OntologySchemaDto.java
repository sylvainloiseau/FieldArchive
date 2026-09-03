package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.List;
import java.util.Map;

public class OntologySchemaDto {
    private final List<OntologyClassDto> types;
    private final List<OntologyPropertyDto> properties;
    private Map<String, List<String>> hierarchy; // classUri -> [superclassUri, ...]


    public OntologySchemaDto(List<OntologyClassDto> types,
                             List<OntologyPropertyDto> properties,
                             Map<String, List<String>> hierarchy) {
        this.types = types;
        this.properties = properties;
        this.hierarchy = hierarchy;

    }

    public List<OntologyClassDto> getTypes() { return types; }
    public List<OntologyPropertyDto> getProperties() { return properties; }
    public Map<String, List<String>> getHierarchy() {
        return hierarchy;
    }
}