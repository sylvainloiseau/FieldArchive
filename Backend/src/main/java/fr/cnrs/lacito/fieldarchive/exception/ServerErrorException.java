package fr.cnrs.lacito.fieldarchive.exception;

/** A failure that is not the caller's fault (mapped to HTTP 500 with its message). */
public class ServerErrorException extends RuntimeException {

    public ServerErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
