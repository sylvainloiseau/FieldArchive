package fr.cnrs.lacito.fieldarchive.core;

/**
 * Published (Spring application event) by every service that changes the data of the open
 * project, so that caches derived from the data (the natural-language usage profile) are
 * invalidated.
 */
public record ProjectDataChangedEvent(String projectName) {
}
