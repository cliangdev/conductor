package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural validation of the slideshow spec a CAROUSEL Creative stores ({@code slideshowSpec}) — a Java
 * subset of the CLI's {@code validateSlideshowSpec}; the CLI validates fully before sending. Pure: the
 * checks that need the database (do the {@code media:<id>} refs resolve) and the Creative (does the slide
 * count match) live in {@link CreativeService}.
 *
 * <p>Rule ids (the {@code ruleId} of the 422 violation): {@value #RULE_KIND}, {@value #RULE_INVALID},
 * {@value #RULE_MEDIA}, {@value #RULE_SLIDE_COUNT}.
 */
public final class SlideshowSpecs {

    public static final String RULE_KIND = "slideshowSpecKind";
    public static final String RULE_INVALID = "slideshowSpecInvalid";
    public static final String RULE_MEDIA = "slideshowSpecMedia";
    public static final String RULE_SLIDE_COUNT = "slideshowSpecSlideCount";

    static final String FIELD = "slideshowSpec";
    static final int ID_MAX = 80;
    static final int MAX_SLIDES = 35;
    /** A cap on the serialized spec, so one request cannot store an arbitrarily large document. */
    static final int MAX_JSON_CHARS = 262_144;
    static final String MEDIA_PREFIX = "media:";

    private static final Pattern MEDIA_REF = Pattern.compile(
            "^media:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    private SlideshowSpecs() {
    }

    /** A problem found in a spec: the request field, the rule id and a message naming it. */
    public record Problem(String field, String ruleId, String message) {
    }

    /** An image field of the spec and the value it holds. */
    public record ImageRef(String field, String value) {
    }

    /** The library id a {@code media:<uuid>} ref names, or null when {@code ref} is not one. */
    public static String mediaId(String ref) {
        if (ref == null) {
            return null;
        }
        Matcher m = MEDIA_REF.matcher(ref);
        return m.matches() ? m.group(1) : null;
    }

    /** Drops a client-sent {@code resolvedStyle} (the resolved style lives in {@code slideStyle}), in place. */
    public static void stripResolvedStyle(ObjectNode spec) {
        spec.remove("resolvedStyle");
        JsonNode style = spec.get("style");
        if (style != null && style.isObject()) {
            ((ObjectNode) style).remove("resolvedStyle");
        }
    }

    /** Structure problems (all {@value #RULE_INVALID}); empty = structurally valid. */
    public static List<Problem> validate(JsonNode spec) {
        List<Problem> out = new ArrayList<>();
        if (spec == null || !spec.isObject()) {
            out.add(invalid(FIELD, "slideshowSpec must be an object with { id, slides }"));
            return out;
        }
        JsonNode id = spec.get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            out.add(invalid(FIELD + ".id", "slideshowSpec.id is required (a non-empty string)"));
        } else if (id.asText().length() > ID_MAX) {
            out.add(invalid(FIELD + ".id", "slideshowSpec.id is longer than " + ID_MAX + " characters"));
        }
        JsonNode grain = spec.get("grain");
        if (grain != null && !grain.isNull() && !grain.isBoolean()) {
            out.add(invalid(FIELD + ".grain", "slideshowSpec.grain must be true or false"));
        }
        JsonNode style = spec.get("style");
        if (style != null && !style.isNull() && !style.isObject()) {
            out.add(invalid(FIELD + ".style", "slideshowSpec.style must be an object"));
        }
        JsonNode slides = spec.get("slides");
        if (slides == null || !slides.isArray() || slides.isEmpty()) {
            out.add(invalid(FIELD + ".slides", "slideshowSpec.slides must be a non-empty array"));
            return out;
        }
        if (slides.size() > MAX_SLIDES) {
            out.add(invalid(FIELD + ".slides", "slideshowSpec.slides has " + slides.size()
                    + " entries; the maximum is " + MAX_SLIDES));
            return out;
        }
        if (spec.toString().length() > MAX_JSON_CHARS) {
            out.add(invalid(FIELD, "slideshowSpec is larger than " + MAX_JSON_CHARS + " characters"));
            return out;
        }
        for (int i = 0; i < slides.size(); i++) {
            if (!slides.get(i).isObject()) {
                out.add(invalid(FIELD + ".slides[" + i + "]", "slideshowSpec.slides[" + i + "] must be an object"));
            }
        }
        for (SlideshowTextFields.TextField f : SlideshowTextFields.list(spec)) {
            if (f.text().length() > SlideshowTextFields.MAX_TEXT_LENGTH) {
                out.add(invalid(FIELD + ".slides[" + (f.slide() - 1) + "]." + f.path(), "slideshowSpec.slides["
                        + (f.slide() - 1) + "]." + f.path() + " is longer than " + SlideshowTextFields.MAX_TEXT_LENGTH
                        + " characters"));
            }
        }
        checkNumbers(spec, FIELD, out);
        for (int i = 0; i < slides.size(); i++) {
            if (slides.get(i).isObject()) {
                checkImageTypes(slides.get(i), FIELD + ".slides[" + i + "]", out);
            }
        }
        return out;
    }

    /** Every image field that holds a string, in document order (non-strings are {@link #validate}'s concern). */
    public static List<ImageRef> imageRefs(JsonNode spec) {
        List<ImageRef> out = new ArrayList<>();
        JsonNode slides = spec == null ? null : spec.get("slides");
        if (slides == null || !slides.isArray()) {
            return out;
        }
        for (int i = 0; i < slides.size(); i++) {
            JsonNode slide = slides.get(i);
            if (!slide.isObject()) {
                continue;
            }
            String at = FIELD + ".slides[" + i + "]";
            addText(slide, "bg", at + ".bg", out);
            addText(slide, "shot", at + ".shot", out);
            forEachObject(slide.get("photos"), (j, photo) -> addText(photo, "src", at + ".photos[" + j + "].src", out));
            forEachObject(slide.get("days"), (j, day) -> addText(day, "img", at + ".days[" + j + "].img", out));
        }
        return out;
    }

    private static void checkImageTypes(JsonNode slide, String at, List<Problem> out) {
        requireTextIfPresent(slide, "bg", at + ".bg", out);
        requireTextIfPresent(slide, "shot", at + ".shot", out);
        forEachObject(slide.get("photos"), (j, photo) -> requireTextIfPresent(photo, "src", at + ".photos[" + j + "].src", out));
        forEachObject(slide.get("days"), (j, day) -> requireTextIfPresent(day, "img", at + ".days[" + j + "].img", out));
    }

    private static void requireTextIfPresent(JsonNode node, String key, String field, List<Problem> out) {
        JsonNode v = node.get(key);
        if (v != null && !v.isNull() && !v.isTextual()) {
            out.add(invalid(field, field + " must be a string"));
        }
    }

    private static void addText(JsonNode node, String key, String field, List<ImageRef> out) {
        JsonNode v = node.get(key);
        if (v != null && v.isTextual()) {
            out.add(new ImageRef(field, v.asText()));
        }
    }

    private static void forEachObject(JsonNode array, java.util.function.BiConsumer<Integer, JsonNode> each) {
        if (array != null && array.isArray()) {
            for (int j = 0; j < array.size(); j++) {
                if (array.get(j).isObject()) {
                    each.accept(j, array.get(j));
                }
            }
        }
    }

    private static void checkNumbers(JsonNode node, String path, List<Problem> out) {
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            out.add(invalid(path, path + " must be a finite number"));
        } else if (node.isContainerNode()) {
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    checkNumbers(node.get(i), path + "[" + i + "]", out);
                }
            } else {
                for (Map.Entry<String, JsonNode> e : node.properties()) {
                    checkNumbers(e.getValue(), path + "." + e.getKey(), out);
                }
            }
        }
    }

    private static Problem invalid(String field, String message) {
        return new Problem(field, RULE_INVALID, message);
    }
}
