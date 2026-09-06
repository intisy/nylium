package io.github.intisy.nylium.api;

public class NyliumException extends RuntimeException {

    public NyliumException(String message) {
        super(message);
    }

    public NyliumException(String message, Throwable cause) {
        super(message, cause);
    }
}
