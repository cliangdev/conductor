package com.conductor.verification;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Per-check status — {@code doctor}'s {@code checks[] {name, status, message}} shape. */
public enum CheckStatus {
    PASS, FAIL, WARN;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
