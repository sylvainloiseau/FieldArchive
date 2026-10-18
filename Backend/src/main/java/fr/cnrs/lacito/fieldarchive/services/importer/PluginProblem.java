package fr.cnrs.lacito.fieldarchive.services.importer;

/**
 * Something found while scanning the plugin directories.
 *
 * @param location the jar or plugin directory concerned
 * @param error    true for a plugin that could not be loaded; false for a note (a bundled
 *                 plugin replaced by the user's one)
 */
public record PluginProblem(String location, String message, boolean error) {
}
