package com.conductor.creative;

import com.conductor.generated.v2.model.SlideshowStyle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation for {@link SlideshowStyle}, shared by Brand Kits ({@code slideshowStyle}) and CAROUSEL
 * Creatives ({@code slideStyle}). The constraints live here rather than as OpenAPI keywords so a
 * violation answers 422 naming the field, not a generic 400 from bean validation. The rules' data is
 * mirrored in {@code conductor-creative/slideshow-style.json} (guarded by {@code SlideshowStylesParityTest}).
 */
public final class SlideshowStyles {

    public record Problem(String field, String message) {
    }

    static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    static final Pattern FAMILY_OK = Pattern.compile("^[^\\p{Cc}\"';{}<>\\\\]+$");
    static final Pattern URL_OK = Pattern.compile("^https://[^\\s\"'<>\\\\]+$");
    static final Set<String> DEFAULT_TEXT = Set.of("box", "dark", "stroke");
    static final int FONT_FAMILY_MAX = 200;
    static final int FONT_URL_MAX = 2048;
    static final int TEXT_SIZE_MIN = 24;
    static final int TEXT_SIZE_MAX = 120;
    static final int SUB_SIZE_MIN = 16;
    static final int SUB_SIZE_MAX = 96;

    private SlideshowStyles() {
    }

    private static final Set<String> KNOWN = Set.of("fontFamily", "fontUrl", "boxBg", "boxInk", "darkBg",
            "darkInk", "strokeInk", "outline", "accent", "defaultText", "textSize", "subSize");

    /**
     * The JSON to store: only the properties that are set (blank strings count as unset, strings are
     * trimmed), or null for an empty style.
     */
    public static JsonNode toStored(ObjectMapper mapper, SlideshowStyle s) {
        if (s == null) {
            return null;
        }
        ObjectNode node = mapper.valueToTree(s);
        node.properties().removeIf(e -> e.getValue().isNull()
                || (e.getValue().isTextual() && e.getValue().asText().isBlank()));
        node.properties().forEach(e -> {
            if (e.getValue().isTextual()) {
                e.setValue(node.textNode(e.getValue().asText().trim()));
            }
        });
        return node.isEmpty() ? null : node;
    }

    /** The API model for a stored style; unknown stored keys are ignored, null, absent or without any known set field gives null. */
    public static SlideshowStyle fromStored(ObjectMapper mapper, JsonNode stored) {
        if (stored == null || stored.isNull() || !stored.isObject()) {
            return null;
        }
        ObjectNode known = mapper.createObjectNode();
        stored.properties().forEach(e -> {
            if (KNOWN.contains(e.getKey())) {
                known.set(e.getKey(), e.getValue());
            }
        });
        SlideshowStyle style = mapper.convertValue(known, SlideshowStyle.class);
        return isEmpty(style) ? null : style;
    }

    /** True when no property is set (null, or a blank string): such a style means "no style". */
    public static boolean isEmpty(SlideshowStyle s) {
        return s == null || (blankToNull(s.getFontFamily()) == null && blankToNull(s.getFontUrl()) == null
                && s.getBoxBg() == null && s.getBoxInk() == null && s.getDarkBg() == null && s.getDarkInk() == null
                && s.getStrokeInk() == null && s.getOutline() == null && s.getAccent() == null
                && s.getDefaultText() == null && s.getTextSize() == null && s.getSubSize() == null);
    }

    /** Problems found, each field prefixed with {@code prefix} (e.g. {@code slideshowStyle}). */
    public static List<Problem> validate(SlideshowStyle s, String prefix) {
        List<Problem> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String family = blankToNull(s.getFontFamily());
        if (family != null) {
            if (family.length() > FONT_FAMILY_MAX) {
                out.add(new Problem(prefix + ".fontFamily", "must be at most " + FONT_FAMILY_MAX + " characters"));
            } else if (Arrays.stream(family.split(",")).noneMatch(f -> !f.isBlank())) {
                out.add(new Problem(prefix + ".fontFamily", "must name at least one font family"));
            } else if (!FAMILY_OK.matcher(family).matches()) {
                out.add(new Problem(prefix + ".fontFamily",
                        "must be a CSS font-family list without quotes, semicolons, braces, angle brackets, backslashes or control characters"));
            }
        }
        String url = blankToNull(s.getFontUrl());
        if (url != null) {
            if (url.length() > FONT_URL_MAX) {
                out.add(new Problem(prefix + ".fontUrl", "must be at most " + FONT_URL_MAX + " characters"));
            } else if (!URL_OK.matcher(url).matches()) {
                out.add(new Problem(prefix + ".fontUrl", "must be an https URL"));
            }
        }
        hex(out, prefix + ".boxBg", s.getBoxBg());
        hex(out, prefix + ".boxInk", s.getBoxInk());
        hex(out, prefix + ".darkBg", s.getDarkBg());
        hex(out, prefix + ".darkInk", s.getDarkInk());
        hex(out, prefix + ".strokeInk", s.getStrokeInk());
        hex(out, prefix + ".outline", s.getOutline());
        hex(out, prefix + ".accent", s.getAccent());
        if (s.getDefaultText() != null && !DEFAULT_TEXT.contains(s.getDefaultText())) {
            out.add(new Problem(prefix + ".defaultText", "must be one of box, dark, stroke"));
        }
        range(out, prefix + ".textSize", s.getTextSize(), TEXT_SIZE_MIN, TEXT_SIZE_MAX);
        range(out, prefix + ".subSize", s.getSubSize(), SUB_SIZE_MIN, SUB_SIZE_MAX);
        return out;
    }

    private static void hex(List<Problem> out, String field, String value) {
        if (value != null && !HEX.matcher(value).matches()) {
            out.add(new Problem(field, "must be a #RRGGBB hex colour"));
        }
    }

    private static void range(List<Problem> out, String field, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            out.add(new Problem(field, "must be between " + min + " and " + max));
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
