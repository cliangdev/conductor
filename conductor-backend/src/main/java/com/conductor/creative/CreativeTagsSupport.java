package com.conductor.creative;

import com.conductor.creative.CreativeValidationException.Violation;
import com.conductor.generated.v2.model.CreativeTags;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that knows a Creative's tag keys: validates a request's {@link CreativeTags} (only the five
 * keys, each a non-blank string of at most {@value #MAX_LENGTH} characters), and converts to and from the stored
 * {@code creative.tags} JSON. Values' vocabularies are the skills' business, not enforced here.
 */
public final class CreativeTagsSupport {

    public static final int MAX_LENGTH = 60;
    public static final List<String> KEYS = List.of("hookType", "angle", "format", "archetype", "hypothesis",
            "lessons", "previewRounds");

    /** {@code lessons}: playbook lesson ids, e.g. {@code #3,#12}. */
    public static final java.util.regex.Pattern LESSONS = java.util.regex.Pattern.compile("^#\\d+(,#\\d+)*$");
    public static final int MAX_PREVIEW_ROUNDS = 50;

    private CreativeTagsSupport() {}

    /** 422 violations naming {@code tags.<key>}; empty for null or valid tags. */
    public static List<Violation> validate(CreativeTags tags) {
        return validate(tags, false);
    }

    /** As above; with {@code blankRemoves} an empty or blank value is allowed (a variant request uses it to drop an inherited key). */
    public static List<Violation> validate(CreativeTags tags, boolean blankRemoves) {
        List<Violation> out = new ArrayList<>();
        if (tags == null) {
            return out;
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        for (String key : KEYS) {
            raw.put(key, valueOf(tags, key));
        }
        Map<String, Object> extra = tags.getAdditionalProperties();
        if (extra != null) {
            for (String key : new java.util.TreeSet<>(extra.keySet())) {
                out.add(new Violation("tags." + key, "tagKey",
                        "Unknown tag \"" + key + "\"; the tags are " + String.join(", ", KEYS)));
            }
        }
        raw.forEach((key, value) -> {
            if (value == null) {
                return;
            }
            String s = value.toString();
            if (s.isBlank() && blankRemoves) {
                return;
            }
            if (s.isBlank()) {
                out.add(new Violation("tags." + key, "tagValue", "tags." + key + " must not be blank"));
            } else if (s.length() > MAX_LENGTH) {
                out.add(new Violation("tags." + key, "tagValue",
                        "tags." + key + " must be at most " + MAX_LENGTH + " characters"));
            } else if ("lessons".equals(key) && !LESSONS.matcher(s).matches()) {
                out.add(new Violation("tags.lessons", "tagValue",
                        "tags.lessons must be comma-separated lesson ids like #3,#12"));
            } else if ("previewRounds".equals(key) && !validRounds(s)) {
                out.add(new Violation("tags.previewRounds", "tagValue",
                        "tags.previewRounds must be a whole number from 0 to " + MAX_PREVIEW_ROUNDS));
            }
        });
        return out;
    }

    /** The JSON to store: only the known, non-null keys, trimmed; {@code {}} for null or empty. */
    public static ObjectNode toStored(ObjectMapper mapper, CreativeTags tags) {
        ObjectNode node = mapper.createObjectNode();
        if (tags == null) {
            return node;
        }
        for (String key : KEYS) {
            String value = valueOf(tags, key);
            if (value != null && !value.isBlank()) {
                node.put(key, value.trim());
            }
        }
        return node;
    }

    /** The descriptive keys a variant inherits from the Creative it is cut from; the rest describe one piece of work. */
    public static final List<String> INHERITED_BY_VARIANTS = List.of("hookType", "angle", "format", "archetype");

    /**
     * A new variant's tags: the descriptive keys of its source, with the request's tags merged over them (a
     * blank value removes a key). Never hypothesis, lessons or previewRounds from the source.
     */
    public static ObjectNode forVariant(ObjectMapper mapper, JsonNode sourceTags, CreativeTags requested) {
        ObjectNode out = mapper.createObjectNode();
        for (String key : INHERITED_BY_VARIANTS) {
            JsonNode v = sourceTags == null ? null : sourceTags.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                out.put(key, v.asText());
            }
        }
        if (requested != null) {
            for (String key : KEYS) {
                String value = valueOf(requested, key);
                if (value == null) {
                    continue;
                }
                if (value.isBlank()) {
                    out.remove(key);
                } else {
                    out.put(key, value.trim());
                }
            }
        }
        return out;
    }

    /** The response model for stored JSON; never null (an empty {@link CreativeTags} when none). */
    public static CreativeTags fromStored(JsonNode stored) {
        CreativeTags tags = new CreativeTags();
        if (stored == null || !stored.isObject()) {
            return tags;
        }
        for (String key : KEYS) {
            JsonNode v = stored.get(key);
            if (v == null || !v.isTextual()) {
                continue;
            }
            switch (key) {
                case "hookType" -> tags.setHookType(v.asText());
                case "angle" -> tags.setAngle(v.asText());
                case "format" -> tags.setFormat(v.asText());
                case "archetype" -> tags.setArchetype(v.asText());
                case "hypothesis" -> tags.setHypothesis(v.asText());
                case "lessons" -> tags.setLessons(v.asText());
                case "previewRounds" -> tags.setPreviewRounds(v.asText());
                default -> { }
            }
        }
        return tags;
    }

    /** The stored tag values of a Creative's JSON, key to value (known keys only). */
    public static Map<String, String> valuesOf(JsonNode stored) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stored == null || !stored.isObject()) {
            return out;
        }
        for (String key : KEYS) {
            JsonNode v = stored.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                out.put(key, v.asText());
            }
        }
        return out;
    }

    private static boolean validRounds(String s) {
        if (!s.matches("^(0|[1-9][0-9]?)$")) {
            return false;
        }
        return Integer.parseInt(s) <= MAX_PREVIEW_ROUNDS;
    }

    private static String valueOf(CreativeTags t, String key) {
        return switch (key) {
            case "hookType" -> t.getHookType();
            case "angle" -> t.getAngle();
            case "format" -> t.getFormat();
            case "archetype" -> t.getArchetype();
            case "hypothesis" -> t.getHypothesis();
            case "lessons" -> t.getLessons();
            case "previewRounds" -> t.getPreviewRounds();
            default -> null;
        };
    }
}
