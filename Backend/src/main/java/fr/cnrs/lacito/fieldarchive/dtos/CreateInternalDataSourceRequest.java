package fr.cnrs.lacito.fieldarchive.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class CreateInternalDataSourceRequest {

    private String shortName;     // ex: "internal"
    private String name;          // ex: "Source interne principale"
    private String description;   // texte libre
}
