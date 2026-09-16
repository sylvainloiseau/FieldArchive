package fr.cnrs.lacito.fieldarchive.dtos;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateDataSourceRequest {
    private String longName;
    private String description;

    // external sources only: path of the RDF file to re-read on sync
    private String sourceLocation;
}
