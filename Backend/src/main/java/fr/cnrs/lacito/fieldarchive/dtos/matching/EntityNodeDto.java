package fr.cnrs.lacito.fieldarchive.dtos.matching;

import java.util.ArrayList;
import java.util.List;

/** An IRI as it appears in one data source, with a snapshot of its label and types. */
public class EntityNodeDto {
    public String dataSource;
    public String iri;
    public String label;
    public List<String> types = new ArrayList<>();

    public EntityNodeDto() {}

    public EntityNodeDto(String dataSource, String iri) {
        this.dataSource = dataSource;
        this.iri = iri;
    }
}
