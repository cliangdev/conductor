package com.conductor.exception;

/** 410 — the resource existed and has expired; asking again will not bring it back. */
public class GoneException extends RuntimeException {

    public GoneException(String message) {
        super(message);
    }
}
