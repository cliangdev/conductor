package com.conductor.creative;

import com.conductor.generated.v2.model.SlideshowStyle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation for {@link SlideshowStyle}, shared by Brand Kits ({@code slideshowStyle}) and CAROUSEL
 * Creatives ({@code slideStyle}). The constraints live here rather than as OpenAPI keywords so a
 * violation answers 422 naming the field, not a generic 400 from bean validation.
 */
final class SlideshowStyles {

    record Problem(String field, String message) {
    }

    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final Set<String> DEFAULT_TEXT = Set.of("box", "dark", "stroke");

    private SlideshowStyles() {
    }

    /** True when no property is set — what an explicit JSON null (or {@code {}}) deserializes to. */
    static boolean isEmpty(SlideshowStyle s) {
        return s == null
                || (s.getFontFamily() == null && s.getFontUrl() == null && s.getBoxBg() == null
                && s.getBoxInk() == null && s.getDarkBg() == null && s.getDarkInk() == null
                && s.getOutline() == null && s.getAccent() == null && s.getDefaultText() == null
                && s.getTextSize() == null && s.getSubSize() == null);
    }

    /** The JSON to store: only the properties that are set, or null for an empty style. */
    static JsonNode toStored(ObjectMapper mapper, SlideshowStyle s) {
        if (isEmpty(s)) {
            return null;
        }
        ObjectNode node = mapper.valueToTree(s);
        node.properties().removeIf(e -> e.getValue().isNull());
        return node;
    }

    /** Problems found, each field prefixed with {@code prefix} (e.g. {@code slideshowStyle}). */
    static List<Problem> validate(SlideshowStyle s, String prefix) {
        List<Problem> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        if (s.getFontFamily() != null && s.getFontFamily().length() > 200) {
            out.add(new Problem(prefix + ".fontFamily", "must be at most 200 characters"));
        }
        if (s.getFontUrl() != null) {
            if (s.getFontUrl().length() > 2048) {
                out.add(new Problem(prefix + ".fontUrl", "must be at most 2048 characters"));
            } else if (!isHttpUrl(s.getFontUrl())) {
                out.add(new Problem(prefix + ".fontUrl", "must be an http(s) URL"));
            }
        }
        hex(out, prefix + ".boxBg", s.getBoxBg());
        hex(out, prefix + ".boxInk", s.getBoxInk());
        hex(out, prefix + ".darkBg", s.getDarkBg());
        hex(out, prefix + ".darkInk", s.getDarkInk());
        hex(out, prefix + ".outline", s.getOutline());
        hex(out, prefix + ".accent", s.getAccent());
        if (s.getDefaultText() != null && !DEFAULT_TEXT.contains(s.getDefaultText())) {
            out.add(new Problem(prefix + ".defaultText", "must be one of box, dark, stroke"));
        }
        range(out, prefix + ".textSize", s.getTextSize(), 24, 120);
        range(out, prefix + ".subSize", s.getSubSize(), 16, 96);
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

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
