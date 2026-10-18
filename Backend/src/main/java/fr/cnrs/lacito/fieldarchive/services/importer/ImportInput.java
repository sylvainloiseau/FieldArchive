package fr.cnrs.lacito.fieldarchive.services.importer;

import java.io.InputStream;
import java.nio.file.Path;

/**
 * What an importer reads: a file or directory on this machine, or (RDF files only) the bytes of
 * an uploaded file. Exactly one of {@code path} and {@code stream} is set.
 *
 * @param fileName the uploaded file's name (used to choose the RDF format), or null
 */
public record ImportInput(Path path, InputStream stream, String fileName) {

    public static ImportInput ofPath(Path path) {
        return new ImportInput(path, null, null);
    }

    public static ImportInput ofUpload(InputStream stream, String fileName) {
        return new ImportInput(null, stream, fileName);
    }

    /** For messages: the path, or the uploaded file's name. */
    public String label() {
        if (path != null) return path.toString();
        return fileName != null ? fileName : "uploaded file";
    }
}
