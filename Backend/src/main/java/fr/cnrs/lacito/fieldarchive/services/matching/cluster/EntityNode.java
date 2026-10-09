package fr.cnrs.lacito.fieldarchive.services.matching.cluster;

import fr.cnrs.lacito.fieldarchive.dtos.matching.EntityNodeDto;

/** An IRI as it appears in one data source. */
public record EntityNode(String dataSource, String iri) {

    public static EntityNode of(EntityNodeDto dto) {
        return new EntityNode(dto.dataSource, dto.iri);
    }

    public EntityNodeDto toDto() {
        return new EntityNodeDto(dataSource, iri);
    }

    @Override
    public String toString() {
        return dataSource + ":" + iri;
    }
}
