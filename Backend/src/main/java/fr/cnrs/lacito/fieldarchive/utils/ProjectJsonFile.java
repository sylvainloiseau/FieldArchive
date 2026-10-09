package fr.cnrs.lacito.fieldarchive.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * A JSON file kept in a project's directory, next to its RDF store (natural-language history,
 * data matching decisions…). Writes are atomic (temporary file then move); an unreadable file
 * is set aside as {@code <name>.corrupt-<millis>} and replaced by an empty value, so a broken
 * file never blocks the feature using it.
 */
public class ProjectJsonFile<T> {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ProjectsDirectory projectsDirectory;
    private final String fileName;
    private final Class<T> type;
    private final Supplier<T> emptyValue;
    private volatile String lastWarning;

    public ProjectJsonFile(ProjectsDirectory projectsDirectory, String fileName, Class<T> type, Supplier<T> emptyValue) {
        this.projectsDirectory = projectsDirectory;
        this.fileName = fileName;
        this.type = type;
        this.emptyValue = emptyValue;
    }

    public String fileName() {
        return fileName;
    }

    public Path fileOf(String projectName) {
        return projectsDirectory.getPublicPath().resolve(projectName).resolve(fileName);
    }

    public boolean exists(String projectName) {
        return Files.exists(fileOf(projectName));
    }

    /** The last "file was unreadable and set aside" message, or null. */
    public String lastWarning() {
        return lastWarning;
    }

    public synchronized T read(String projectName) {
        Path file = fileOf(projectName);
        if (!Files.exists(file)) return emptyValue.get();
        try {
            T value = MAPPER.readValue(file.toFile(), type);
            return value != null ? value : emptyValue.get();
        } catch (IOException e) {
            Path aside = file.resolveSibling(fileName + ".corrupt-" + Instant.now().toEpochMilli());
            try {
                Files.move(file, aside);
            } catch (IOException ignored) {
                // nothing more we can do; the next write replaces the file
            }
            lastWarning = "Unreadable " + fileName + " moved to " + aside.getFileName() + ": " + e.getMessage();
            System.err.println("WARNING: " + lastWarning);
            return emptyValue.get();
        }
    }

    public synchronized void write(String projectName, T value) {
        Path file = fileOf(projectName);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(fileName + ".tmp");
            MAPPER.writeValue(tmp.toFile(), value);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    public synchronized void delete(String projectName) {
        try {
            Files.deleteIfExists(fileOf(projectName));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + fileOf(projectName) + ": " + e.getMessage(), e);
        }
    }

    /** Raw bytes of the file, or null when there is none (for backups). */
    public byte[] rawContent(String projectName) {
        Path f = fileOf(projectName);
        try {
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Restores the file from a backup. */
    public synchronized void restore(String projectName, byte[] content) throws IOException {
        Path f = fileOf(projectName);
        Files.createDirectories(f.getParent());
        Files.write(f, content);
    }
}
