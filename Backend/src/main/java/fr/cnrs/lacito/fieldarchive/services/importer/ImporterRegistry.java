package fr.cnrs.lacito.fieldarchive.services.importer;

import fr.cnrs.lacito.fieldarchive.services.importer.ImporterPluginScanner.PluginDirectory;
import fr.cnrs.lacito.fieldarchive.services.importer.ImporterPluginScanner.ScanResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * The importers available to external data sources: "Sync with an RDF file" plus the plugins of
 * the two {@code ricImporterPlugins} directories (bundled with the application, and the user's
 * one in {@code $FIELD_ARCHIVE_DATA}). Scanned at startup and on {@link #rescan()}.
 */
@Service
public class ImporterRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ImporterRegistry.class);

    public static final String DIRECTORY_NAME = "ricImporterPlugins";

    /** Old {@code app:sourceTool} value meaning "an RDF file", from the former form. */
    private static final String LEGACY_RDF_FILE = "other";

    public enum Status { RDF_FILE, PLUGIN, MISSING }

    /** How a stored {@code app:sourceTool} value resolves; {@code importer} is null when MISSING. */
    public record Resolution(Status status, Importer importer, String rawValue) {
        /** The id to store and show: the importer's, or the unknown raw value. */
        public String id() {
            return importer != null ? importer.id() : rawValue;
        }
    }

    /** One immutable state of the registry. */
    public record Snapshot(List<Importer> importers, List<PluginProblem> problems, Path bundledDirectory,
                           Path userDirectory) {
    }

    private final RdfFileImporter rdfFileImporter = new RdfFileImporter();
    private final Path bundledDirectory;
    private final Path userDirectory;
    /** Imports hold the read lock, so that a rescan never closes a class loader in use. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile Snapshot snapshot;
    private List<URLClassLoader> loaders = List.of();

    @Autowired
    public ImporterRegistry(@Value("${fieldarchive.importers.bundled-dir:}") String bundledDir,
                            @Value("${fieldarchive.importers.user-dir:}") String userDir) {
        this(bundledDir.isBlank() ? defaultBundledDirectory() : Path.of(bundledDir),
                userDir.isBlank() ? defaultUserDirectory() : Path.of(userDir));
    }

    public ImporterRegistry(Path bundledDirectory, Path userDirectory) {
        this.bundledDirectory = bundledDirectory;
        this.userDirectory = userDirectory;
        if (userDirectory != null) {
            try {
                Files.createDirectories(userDirectory);
            } catch (IOException e) {
                LOG.warn("Cannot create the importer plugin directory {}: {}", userDirectory, e.getMessage());
            }
        }
        rescan();
    }

    /** {@code $FIELD_ARCHIVE_DATA/ricImporterPlugins}, or null without FIELD_ARCHIVE_DATA. */
    static Path defaultUserDirectory() {
        String data = System.getenv("FIELD_ARCHIVE_DATA");
        return data == null || data.isBlank() ? null : Path.of(data, DIRECTORY_NAME);
    }

    /**
     * The {@code ricImporterPlugins} directory next to the running jar ({@code java -jar}), or
     * next to {@code target/classes} ({@code mvn spring-boot:run}, tests): the first class path
     * entry that has one.
     */
    static Path defaultBundledDirectory() {
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path parent = Path.of(entry).toAbsolutePath().getParent();
            if (parent == null) continue;
            Path candidate = parent.resolve(DIRECTORY_NAME);
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /** Scans the directories again; the importers of the previous scan are discarded. */
    public Snapshot rescan() {
        ScanResult result = new ImporterPluginScanner(ImporterRegistry.class.getClassLoader()).scan(List.of(
                new PluginDirectory(bundledDirectory, ImporterOrigin.BUNDLED),
                new PluginDirectory(userDirectory, ImporterOrigin.USER)));

        List<Importer> importers = new ArrayList<>(result.plugins());
        importers.sort(Comparator.comparing(i -> i.label().toLowerCase(Locale.ROOT)));
        importers.add(rdfFileImporter);
        Snapshot next = new Snapshot(List.copyOf(importers), result.problems(), bundledDirectory, userDirectory);

        lock.writeLock().lock();
        try {
            List<URLClassLoader> previous = loaders;
            snapshot = next;
            loaders = result.loaders();
            previous.forEach(ImporterPluginScanner::closeQuietly);
        } finally {
            lock.writeLock().unlock();
        }
        LOG.info("Importer plugins: {} loaded {}, {} problem(s); directories {} and {}",
                result.plugins().size(), result.plugins().stream().map(Importer::id).toList(),
                result.problems().stream().filter(PluginProblem::error).count(), bundledDirectory, userDirectory);
        result.problems().forEach(p -> LOG.warn("Importer plugin {}: {}", p.location(), p.message()));
        return next;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public RdfFileImporter rdfFileImporter() {
        return rdfFileImporter;
    }

    /** An importer by id, ignoring case. */
    public Optional<Importer> find(String id) {
        if (id == null) return Optional.empty();
        String key = id.trim().toLowerCase(Locale.ROOT);
        return snapshot.importers().stream().filter(i -> i.id().toLowerCase(Locale.ROOT).equals(key)).findFirst();
    }

    /**
     * Reads a stored {@code app:sourceTool} value: nothing, {@code ""} and the former
     * {@code Other} mean an RDF file; a plugin name (any case) that plugin; anything else
     * (Tropy, Gramps, an uninstalled plugin) a missing importer.
     */
    public Resolution resolve(String stored) {
        String value = stored == null ? "" : stored.trim();
        if (value.isEmpty() || value.equalsIgnoreCase(LEGACY_RDF_FILE) || value.equalsIgnoreCase(RdfFileImporter.ID)) {
            return new Resolution(Status.RDF_FILE, rdfFileImporter, value);
        }
        return find(value)
                .map(i -> new Resolution(Status.PLUGIN, i, value))
                .orElseGet(() -> new Resolution(Status.MISSING, null, value));
    }

    /** Runs {@code work} (an import) while no rescan can close the plugins' class loaders. */
    public <T> T use(Supplier<T> work) {
        lock.readLock().lock();
        try {
            return work.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    @PreDestroy
    void close() {
        lock.writeLock().lock();
        try {
            loaders.forEach(ImporterPluginScanner::closeQuietly);
            loaders = List.of();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
