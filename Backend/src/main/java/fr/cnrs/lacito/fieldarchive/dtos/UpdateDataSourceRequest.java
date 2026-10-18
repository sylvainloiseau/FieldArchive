package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateDataSourceRequest {
    private String longName;
    private String description;

    // external sources only: path of the RDF file to re-read on sync
    private String sourceLocation;

    // How the source is imported: a plugin name or "rdf-file" (default: unchanged, or rdf-file on creation)
    private String importer;
    // Plugin options (name -> value); null = unchanged
    private Map<String, String> importerOptions;
    // Prefix of the IRIs minted by a plugin; null = unchanged
    private String importBaseIri;
}
