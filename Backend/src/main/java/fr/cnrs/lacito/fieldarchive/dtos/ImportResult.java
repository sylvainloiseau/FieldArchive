package fr.cnrs.lacito.fieldarchive.dtos;

import java.time.OffsetDateTime;

public class ImportResult {
    public boolean success;
    public String sourceName;
    public long tripleCount;
    public OffsetDateTime importedAt;
    public String message;

    public static ImportResult success(String sourceName, long tripleCount) {
        ImportResult r = new ImportResult();
        r.success = true;
        r.sourceName = sourceName;
        r.tripleCount = tripleCount;
        r.importedAt = OffsetDateTime.now();
        return r;
    }
}
