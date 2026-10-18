package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateExternalDataSourceRequest {

    private String shortName;
    private String name;
    private String description;

    // For ExternalDataSource
    private String sourceLocation;  // chemin ou URL

    // How the source is imported: a plugin name or "rdf-file" (default: unchanged, or rdf-file on creation)
    private String importer;
    // Plugin options (name -> value); null = unchanged
    private Map<String, String> importerOptions;
    // Prefix of the IRIs minted by a plugin; null = unchanged
    private String importBaseIri;
}
