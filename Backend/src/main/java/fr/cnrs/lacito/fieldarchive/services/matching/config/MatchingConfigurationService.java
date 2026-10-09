package fr.cnrs.lacito.fieldarchive.services.matching.config;

import fr.cnrs.lacito.fieldarchive.core.ProjectContext;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationEnvelopeDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.ValidationReportDto;
import fr.cnrs.lacito.fieldarchive.exception.BadRequestException;
import fr.cnrs.lacito.fieldarchive.exception.InvalidConfigurationException;
import fr.cnrs.lacito.fieldarchive.utils.ProjectsDirectory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * The matching configuration of the open project: {@code <project dir>/matching-configuration.json}
 * when it exists, else the bundled default. Cached, and reloaded when the file's modification
 * time changes, so a hand edit is picked up without a restart.
 */
@Service
public class MatchingConfigurationService {

    public static final String FILE_NAME = "matching-configuration.json";
    public static final String DEFAULT_RESOURCE = "matching/default-matching-configuration.json";

    private final ProjectsDirectory projectsDirectory;
    private MatchingConfigurationDto defaultConfiguration;

    private String cachedProject;
    private FileTime cachedModified;
    private MatchingConfigurationDto cachedDto;
    private MatchingConfiguration compiled;

    public MatchingConfigurationService(ProjectsDirectory projectsDirectory) {
        this.projectsDirectory = projectsDirectory;
    }

    // =========================
    //  Reading
    // =========================

    /** The bundled default (a fresh copy). */
    public synchronized MatchingConfigurationDto defaultConfiguration() {
        if (defaultConfiguration == null) {
            try (InputStream in = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
                defaultConfiguration = MatchingConfigurationLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + DEFAULT_RESOURCE + ": " + e.getMessage(), e);
            }
        }
        return MatchingConfigurationLoader.copy(defaultConfiguration);
    }

    public Path fileOf(String projectName) {
        return projectsDirectory.getPublicPath().resolve(projectName).resolve(FILE_NAME);
    }

    /** "project" when the project has its own file, else "default". */
    public String origin() {
        return Files.exists(fileOf(currentProject())) ? "project" : "default";
    }

    /**
     * The configuration as written (full form), whether valid or not.
     *
     * @throws BadRequestException when the project file is not readable JSON
     */
    public synchronized MatchingConfigurationDto currentConfiguration() {
        String project = currentProject();
        Path file = fileOf(project);
        if (!Files.exists(file)) return defaultConfiguration();
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            if (!project.equals(cachedProject) || !Objects.equals(modified, cachedModified) || cachedDto == null) {
                cachedDto = MatchingConfigurationLoader.parse(Files.readString(file, StandardCharsets.UTF_8));
                cachedProject = project;
                cachedModified = modified;
            }
            return MatchingConfigurationLoader.copy(cachedDto);
        } catch (IOException | IllegalArgumentException e) {
            throw new BadRequestException("The project's " + FILE_NAME + " cannot be read (" + e.getMessage()
                    + "). Fix or reset it in the matching settings.");
        }
    }

    /**
     * The configuration the algorithm uses.
     *
     * @throws InvalidConfigurationException when the project configuration has errors
     */
    public MatchingConfiguration effectiveConfiguration() {
        MatchingConfigurationDto dto = currentConfiguration();
        ValidationReportDto report = MatchingConfigurationValidator.validate(dto, null);
        if (report.hasErrors()) {
            throw new InvalidConfigurationException(
                    "The project's matching configuration has errors; fix them in the matching settings.", report);
        }
        String hash = MatchingConfigurationLoader.hash(dto);
        synchronized (this) {
            // the same object while the configuration is unchanged: caches keyed by it stay valid
            if (compiled == null || !compiled.hash().equals(hash)) compiled = MatchingConfigurationLoader.compile(dto);
            return compiled;
        }
    }

    public MatchingConfigurationEnvelopeDto envelope(MatchingConfigurationDto dto, String origin,
                                                     MatchingConfigurationValidator.DataContext data) {
        MatchingConfigurationEnvelopeDto e = new MatchingConfigurationEnvelopeDto();
        e.configuration = dto;
        e.origin = origin;
        e.hash = MatchingConfigurationLoader.hash(dto);
        e.validation = MatchingConfigurationValidator.validate(dto, data);
        return e;
    }

    // =========================
    //  Writing
    // =========================

    /** Validates and writes the project file. @throws InvalidConfigurationException on errors */
    public synchronized MatchingConfigurationDto save(MatchingConfigurationDto dto) {
        MatchingConfigurationDto normalized = MatchingConfigurationLoader.normalize(dto);
        ValidationReportDto report = MatchingConfigurationValidator.validate(normalized, null);
        if (report.hasErrors()) {
            throw new InvalidConfigurationException("The configuration has errors and was not saved.", report);
        }
        Path file = fileOf(currentProject());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, MatchingConfigurationWriter.toFileJson(normalized), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
        cachedDto = null;
        return normalized;
    }

    /** Deletes the project file: the default applies again. */
    public synchronized void reset() {
        try {
            Files.deleteIfExists(fileOf(currentProject()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot delete " + FILE_NAME + ": " + e.getMessage(), e);
        }
        cachedDto = null;
    }

    /** The effective configuration as it would be written in the project file. */
    public byte[] exportBytes() {
        return MatchingConfigurationWriter.toFileJson(currentConfiguration()).getBytes(StandardCharsets.UTF_8);
    }

    /** Parses an imported file (any accepted form). @throws BadRequestException when it is not a configuration */
    public MatchingConfigurationDto parseImport(String json) {
        try {
            return MatchingConfigurationLoader.parse(json);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    // =========================
    //  Backups
    // =========================

    public byte[] rawContent(String projectName) {
        Path f = fileOf(projectName);
        try {
            return Files.exists(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public synchronized void restore(String projectName, byte[] content) throws IOException {
        Path f = fileOf(projectName);
        Files.createDirectories(f.getParent());
        Files.write(f, content);
        cachedDto = null;
    }

    private static String currentProject() {
        if (!ProjectContext.isOpen()) throw new BadRequestException("No project is open.");
        return ProjectContext.getProjectName();
    }
}
