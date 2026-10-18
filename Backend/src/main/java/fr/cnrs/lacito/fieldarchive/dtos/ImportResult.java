package fr.cnrs.lacito.fieldarchive.dtos;

import java.time.OffsetDateTime;
import java.util.List;

public class ImportResult {
    public boolean success;
    public String sourceName;
    public long tripleCount;
    public OffsetDateTime importedAt;
    public String message;
    /** The importer used (a plugin name or "rdf-file"). */
    public String importer;
    /** Warnings logged by the importer during the import. */
    public List<String> warnings = List.of();

    public static ImportResult success(String sourceName, long tripleCount) {
        ImportResult r = new ImportResult();
        r.success = true;
        r.sourceName = sourceName;
        r.tripleCount = tripleCount;
        r.importedAt = OffsetDateTime.now();
        return r;
    }
}
