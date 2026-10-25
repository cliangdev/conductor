package com.conductor.creative;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** A refused render dispatch: a stable {@code code} the web app switches on, plus extra problem properties. */
public class CreativeRenderDispatchException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    public CreativeRenderDispatchException(HttpStatus status, String code, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.code = code;
        this.properties = properties;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
