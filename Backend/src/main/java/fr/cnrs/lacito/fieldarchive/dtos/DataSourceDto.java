package fr.cnrs.lacito.fieldarchive.dtos;



import java.util.Map;

public class DataSourceDto {
    public String id;
    public String shortName;
    public String longName;
    public String description;

    public String type;          // internal | external
    public boolean editable;

    public String graphIri;      // urn:datasource:xxx
    public String sourceTool;    // Tropy, Lameta, Gramps
    public String sourceLocation;// URL / path

    public String createdAt;
    public String lastSync;
    /** Number of triples in the source's named graph. */
    public long tripleCount;

    // External sources: how they are imported (services/importer/)
    /** Importer id (a plugin name or "rdf-file"), or the stored value when no plugin has it. */
    public String importer;
    /** RDF_FILE, PLUGIN or MISSING (no plugin for the stored value). */
    public String importerStatus;
    public Map<String, String> importerOptions;
    /** Prefix of the IRIs minted by a plugin; null until set or first imported. */
    public String importBaseIri;
    /** The base IRI proposed when none is set: {@code <project prefix>/source/<shortName>}. */
    public String importBaseIriDefault;
    public LastImportDto lastImport;
}