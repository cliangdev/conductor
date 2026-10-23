package com.conductor.service;

import com.conductor.exception.BusinessException;
import com.conductor.exception.MissingFieldsException;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates and merges a Work Item's typed {@code fields} against the fields its bound Workflow declares,
 * and answers which fields a status requires. Stateless; the Workflow's {@link Statechart} is the only
 * authority on what is valid.
 */
final class WorkItemFields {

    private static final int MAX_TEXT = 500;
    private static final int MAX_LONGTEXT = 20_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkItemFields() {
    }

    /**
     * The stored object after applying {@code incoming} to {@code existing}: PATCH semantics per key, where
     * a key sent with a value sets it, a key sent as {@code null} (or a blank string) clears it, and a key
     * not sent is left alone. Every problem is reported in one {@link BusinessException}. A null or empty
     * {@code incoming} returns {@code existing} untouched; a Workflow that declares no fields rejects any
     * non-empty payload.
     */
    static JsonNode merge(Statechart statechart, JsonNode existing, Map<String, Object> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing;
        }
        if (statechart.fields().isEmpty()) {
            throw new BusinessException("Workflow " + statechart.slug() + " defines no fields");
        }
        ObjectNode merged = existing != null && existing.isObject()
                ? ((ObjectNode) existing).deepCopy() : JsonNodeFactory.instance.objectNode();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            StatechartField field = statechart.field(key).orElse(null);
            if (field == null) {
                problems.add("unknown field '" + key + "'");
                continue;
            }
            JsonNode value = MAPPER.valueToTree(entry.getValue());
            if (isEmpty(value)) {
                merged.remove(key);
                continue;
            }
            String problem = check(field, value);
            if (problem != null) {
                problems.add(problem);
            } else {
                merged.set(key, normalize(value));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException("Invalid fields: " + String.join("; ", problems)
                    + ". Valid keys: " + statechart.fields().stream().map(StatechartField::key).toList());
        }
        return merged.isEmpty() ? null : merged;
    }

    /** Keys of fields the Workflow requires in {@code status} that {@code fields} leaves empty, in declared order. */
    static List<String> missingRequired(Statechart statechart, JsonNode fields, String status) {
        List<String> missing = new ArrayList<>();
        for (StatechartField field : statechart.fields()) {
            if (field.requiredIn().contains(status)
                    && (fields == null || isEmpty(fields.get(field.key())))) {
                missing.add(field.key());
            }
        }
        return missing;
    }

    /** Refuses a move into {@code status} while required fields are empty. */
    static void requireFilled(Statechart statechart, JsonNode fields, String status) {
        List<String> missing = missingRequired(statechart, fields, status);
        if (!missing.isEmpty()) {
            throw new MissingFieldsException(status, missing);
        }
    }

    private static boolean isEmpty(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode()
                || (value.isTextual() && value.asText().isBlank());
    }

    /** Text values are stored trimmed; everything else as sent. */
    private static JsonNode normalize(JsonNode value) {
        return value.isTextual() ? JsonNodeFactory.instance.textNode(value.asText().trim()) : value;
    }

    /** A message when {@code value} is not valid for {@code field}'s type, else null. */
    private static String check(StatechartField field, JsonNode value) {
        String key = field.key();
        switch (field.type()) {
            case "text":
                return textProblem(key, value, MAX_TEXT);
            case "longtext":
                return textProblem(key, value, MAX_LONGTEXT);
            case "number":
                return value.isNumber() ? null : "'" + key + "' must be a number";
            case "date":
                if (!value.isTextual()) {
                    return "'" + key + "' must be a date (yyyy-MM-dd)";
                }
                try {
                    LocalDate.parse(value.asText().trim());
                    return null;
                } catch (DateTimeParseException e) {
                    return "'" + key + "' must be a date (yyyy-MM-dd)";
                }
            case "enum":
                return value.isTextual() && field.options().contains(value.asText().trim())
                        ? null : "'" + key + "' must be one of " + field.options();
            case "url":
                return value.isTextual() && isHttpUrl(value.asText().trim())
                        ? null : "'" + key + "' must be an http(s) URL";
            default:
                return "'" + key + "' has an unsupported type " + field.type();
        }
    }

    private static String textProblem(String key, JsonNode value, int max) {
        if (!value.isTextual()) {
            return "'" + key + "' must be text";
        }
        return value.asText().length() > max ? "'" + key + "' is longer than " + max + " characters" : null;
    }

    private static boolean isHttpUrl(String text) {
        try {
            URI uri = URI.create(text);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
