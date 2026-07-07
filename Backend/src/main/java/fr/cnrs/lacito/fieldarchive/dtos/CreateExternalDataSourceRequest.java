package fr.cnrs.lacito.fieldarchive.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateExternalDataSourceRequest {

    private String shortName;
    private String name;
    private String description;

    // spécifique aux sources externes
    private String sourceTool;      // ex: "Tropy", "Lameta"
    private String sourceLocation;  // chemin ou URL
}
