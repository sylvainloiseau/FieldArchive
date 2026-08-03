package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.List;

public class OntologySchemaDto {
    private final List<OntologyClassDto> types;
    private final List<OntologyPropertyDto> properties;

    public OntologySchemaDto(List<OntologyClassDto> types, List<OntologyPropertyDto> properties) {
        this.types = types;
        this.properties = properties;
    }

    public List<OntologyClassDto> getTypes() { return types; }
    public List<OntologyPropertyDto> getProperties() { return properties; }
}