package fr.cnrs.lacito.fieldarchive.dtos;


import lombok.Getter;
import lombok.Setter;

public class CreateDataSourceRequest {

    private String name;        // nom lisible
    private String description; // description éditable
    private String contextName; // nom du sous-graphe (ex: "internal1")

}