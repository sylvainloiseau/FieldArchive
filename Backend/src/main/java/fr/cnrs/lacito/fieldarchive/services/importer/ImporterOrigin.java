package fr.cnrs.lacito.fieldarchive.services.importer;

/** Where an importer comes from. */
public enum ImporterOrigin {
    /** Part of the backend: "Sync with an RDF file". */
    BUILT_IN,
    /** A plugin shipped with the application (the bundled {@code ricImporterPlugins} directory). */
    BUNDLED,
    /** A plugin the user put in {@code $FIELD_ARCHIVE_DATA/ricImporterPlugins}. */
    USER
}
