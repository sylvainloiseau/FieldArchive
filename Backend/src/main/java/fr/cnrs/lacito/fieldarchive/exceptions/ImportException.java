package fr.cnrs.lacito.fieldarchive.exceptions;

public class ImportException extends RuntimeException{
    private final Long lineNumber;
    private final Long columnNumber;

    public ImportException(String message) {
        super(message);
        this.lineNumber = null;
        this.columnNumber = null;
    }

    public ImportException(long lineNumber, long columnNumber, String message) {
        super(message);
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
    }

    public Long getLineNumber() { return lineNumber; }
    public Long getColumnNumber() { return columnNumber; }
}
