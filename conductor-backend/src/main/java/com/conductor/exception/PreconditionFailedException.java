package com.conductor.exception;

/** 412 — an If-Match precondition no longer holds (the resource changed since the client read it). */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(String message) {
        super(message);
    }
}
