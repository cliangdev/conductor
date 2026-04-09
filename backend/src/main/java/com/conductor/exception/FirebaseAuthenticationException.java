package com.conductor.exception;

public class FirebaseAuthenticationException extends RuntimeException {

    public FirebaseAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
