package fr.cnrs.lacito.fieldarchive.dtos;

import java.util.List;

/** Payloads of {@code /importers}. */
public final class ImporterDtos {

    private ImporterDtos() {
    }

    /** One plugin option, as described by the plugin. */
    public record ImporterOptionDto(String name, String description, String defaultValue) {
    }

    /**
     * @param inputKind {@code FILE}, {@code DIRECTORY} or {@code FILE_OR_DIRECTORY}
     * @param origin    {@code BUILT_IN}, {@code BUNDLED} or {@code USER}
     * @param location  the plugin's jar or directory; null for the built-in importer
     */
    public record ImporterDto(String id, String label, String description, List<ImporterOptionDto> options,
                              String inputKind, String origin, String version, String location) {
    }

    /** @param error false for a note (a bundled plugin replaced by the user's one) */
    public record PluginProblemDto(String location, String message, boolean error) {
    }

    /** @param bundled null when no bundled directory was found; user null without FIELD_ARCHIVE_DATA */
    public record PluginDirectoriesDto(String bundled, String user) {
    }

    /** The plugins by label, then "Sync with an RDF file" last. */
    public record ImportersDto(List<ImporterDto> importers, List<PluginProblemDto> problems,
                               PluginDirectoriesDto directories) {
    }
}
