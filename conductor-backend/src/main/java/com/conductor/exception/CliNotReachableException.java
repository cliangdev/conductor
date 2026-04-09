package com.conductor.exception;

public class CliNotReachableException extends RuntimeException {

    public CliNotReachableException(String message) {
        super(message);
    }

    public CliNotReachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
