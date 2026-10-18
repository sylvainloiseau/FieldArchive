package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.List;

/** Summary of the last successful import of an external data source (stored as {@code app:lastImport}). */
public class LastImportDto {
    public String importer;
    /** Version of the plugin, null for an RDF file. */
    public String importerVersion;
    public long statements;
    public String date;
    /** The path read, or the uploaded file's name. */
    public String input;
    public List<String> warnings;
}
