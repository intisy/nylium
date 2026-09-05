package io.github.intisy.rutter.api;

public class RutterException extends RuntimeException {

    public RutterException(String message) {
        super(message);
    }

    public RutterException(String message, Throwable cause) {
        super(message, cause);
    }
}
