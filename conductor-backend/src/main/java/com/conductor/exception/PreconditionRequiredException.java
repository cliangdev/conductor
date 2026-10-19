package com.conductor.exception;

/** 428 — the request must be conditional (carry If-Match) and did not. */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException(String message) {
        super(message);
    }
}
