package fr.cnrs.lacito.fieldarchive.services.nlquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import fr.cnrs.lacito.fieldarchive.utils.ProjectsDirectory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * API keys of the paid agents. An environment variable wins; otherwise the key saved from the
 * dialog in {@code $FIELD_ARCHIVE_DATA/settings/llm-keys.json} (outside {@code projects/}, so it
 * is never part of an export or a backup). A packaged app started from the Dock or the Start
 * menu does not see the shell's environment, which is why the file exists.
 */
@Component
public class ApiKeyStore {

    public static final String FILE_NAME = "llm-keys.json";
    private static final Map<String, String> ENV_VARS = Map.of(
            "claude", "ANTHROPIC_API_KEY",
            "openai", "OPENAI_API_KEY");

    private final ProjectsDirectory projectsDirectory;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ApiKeyStore(ProjectsDirectory projectsDirectory) {
        this.projectsDirectory = projectsDirectory;
    }

    private Path file() {
        return projectsDirectory.getPublicPath().getParent().resolve("settings").resolve(FILE_NAME);
    }

    public Optional<String> key(String provider) {
        String env = envKey(provider);
        if (env != null) return Optional.of(env);
        String saved = readAll().get(provider);
        return saved == null || saved.isBlank() ? Optional.empty() : Optional.of(saved);
    }

    /** "environment", "settings" or null. */
    public String keySource(String provider) {
        if (envKey(provider) != null) return "environment";
        String saved = readAll().get(provider);
        return saved == null || saved.isBlank() ? null : "settings";
    }

    private String envKey(String provider) {
        String var = ENV_VARS.get(provider);
        String v = var == null ? null : System.getenv(var);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private synchronized Map<String, String> readAll() {
        Path f = file();
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            System.err.println("WARNING: unreadable " + f + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public synchronized void save(String provider, String key) {
        Map<String, String> all = readAll();
        all.put(provider, key.trim());
        writeAll(all);
    }

    public synchronized void delete(String provider) {
        Map<String, String> all = readAll();
        if (all.remove(provider) != null) writeAll(all);
    }

    private void writeAll(Map<String, String> all) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(FILE_NAME + ".tmp");
            mapper.writeValue(tmp.toFile(), all);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // not a POSIX filesystem (Windows): the user's profile folder is already private
            }
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + f + ": " + e.getMessage(), e);
        }
    }
}
