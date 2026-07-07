package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

public class UpdateRdfEntityRequest {
    // IMPORTANT: on remplace uniquement les prédicats fournis
    public List<RdfPropertyDto> properties = new ArrayList<>();
}
