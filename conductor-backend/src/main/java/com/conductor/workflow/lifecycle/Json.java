package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Null-safe readers for parsing a Workflow {@code definition} JSON document into the immutable
 * {@link Statechart} value objects. Package-private — the lifecycle domain's only JSON-shaped seam.
 */
final class Json {

    private Json() {
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static boolean bool(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.asBoolean(false);
    }

    static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    static List<String> stringList(JsonNode node, String field) {
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        arr.forEach(e -> out.add(e.asText()));
        return List.copyOf(out);
    }
}
