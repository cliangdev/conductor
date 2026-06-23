package com.conductor.integration;

import java.util.Map;

/** Result of invoking an outbound action (forward-looking seam). */
public record ActionResult(boolean success, String message, Map<String, Object> output) {
    public static ActionResult ok(Map<String, Object> output) { return new ActionResult(true, null, output); }
    public static ActionResult error(String message) { return new ActionResult(false, message, Map.of()); }
}
