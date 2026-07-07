package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.ArrayList;
import java.util.List;

public class CreateRdfEntityRequest {
    public List<String> types = new ArrayList<>();
    public List<RdfPropertyDto> properties = new ArrayList<>();
}