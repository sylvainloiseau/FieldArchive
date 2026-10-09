package fr.cnrs.lacito.fieldarchive.dtos.matching;

/** A configuration as returned by the API, with where it comes from and its validation. */
public class MatchingConfigurationEnvelopeDto {
    public MatchingConfigurationDto configuration;
    /** "project" (the project file) or "default" (the bundled configuration). */
    public String origin;
    public String hash;
    public ValidationReportDto validation;
}
