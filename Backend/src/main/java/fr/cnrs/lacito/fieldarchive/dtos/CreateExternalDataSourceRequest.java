package fr.cnrs.lacito.fieldarchive.dtos;

import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Getter
@Setter
public class CreateExternalDataSourceRequest {

    private String shortName;
    private String name;
    private String description;

    // spécifique aux sources externes
    private String sourceLocation;  // chemin ou URL
}
