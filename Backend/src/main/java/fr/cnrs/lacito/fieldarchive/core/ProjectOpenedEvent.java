package fr.cnrs.lacito.fieldarchive.core;

/**
 * Published (Spring application event) once a project has been opened, created or restored
 * from a backup, so that state derived from the project files (the {@code owl:sameAs} graph
 * of the data matching) can be brought up to date.
 */
public record ProjectOpenedEvent(String projectName) {
}
