package fr.cnrs.lacito.fieldarchive.exception;

import fr.cnrs.lacito.fieldarchive.dtos.matching.ValidationReportDto;

/** A configuration that cannot be saved or used; the report says where and why (HTTP 400). */
public class InvalidConfigurationException extends RuntimeException {

    private final ValidationReportDto report;

    public InvalidConfigurationException(String message, ValidationReportDto report) {
        super(message);
        this.report = report;
    }

    public ValidationReportDto getReport() {
        return report;
    }
}
