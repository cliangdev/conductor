package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The editable text of a slideshow spec ({@code {id, slides:[...]}}): which strings on a slide a person may
 * change, listing them, replacing them, and diffing two specs. Pure: it never touches a database and
 * {@link #apply} never mutates its input.
 *
 * <p>A <em>pattern</em> is a dotted path from one slide with {@code []} meaning "any index of that array"
 * (e.g. {@code rows[].items[]}). A <em>concrete path</em> ({@code rows[1].items[2]}) is editable when
 * replacing each index with {@code []} gives a pattern AND the value at that path is already a string. Edits
 * only replace existing strings: they never add or remove array entries or create absent fields.
 *
 * <p>The patterns are mirrored in {@code conductor-creative/slideshow-text-fields.json} (guarded by
 * {@code SlideshowTextFieldsParityTest}); the CLI and the web app read the same file.
 */
public final class SlideshowTextFields {

    /** Every editable pattern, in the order {@link #list} reports a slide's fields. */
    static final List<String> PATTERNS = List.of(
            "texts[].t",
            "callouts[].label",
            "title",
            "date",
            "items[]",
            "items[].t",
            "paras[]",
            "photos[].badge",
            "photos[].label",
            "rows[].k",
            "rows[].items[]",
            "days[].d",
            "days[].t",
            "days[].icon",
            "head",
            "sections[].h",
            "sections[].items[]",
            "count",
            "ex",
            "lines[].k",
            "lines[].v",
            "bubbles[].t");

    /** The longest an editable string may be. */
    static final int MAX_TEXT_LENGTH = 500;

    private static final Set<String> PATTERN_SET = new LinkedHashSet<>(PATTERNS);
    private static final Pattern INDEX = Pattern.compile("\\[\\d+]");
    private static final Pattern CONCRETE_PATH = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(\\[\\d{1,6}])*(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\d{1,6}])*)*$");
    private static final Pattern STEP = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)]");

    private SlideshowTextFields() {
    }

    /** One editable string: its 1-based slide number, its path within the slide, and its current text. */
    public record TextField(int slide, String path, String text) {
    }

    /** One requested replacement: the 1-based slide, a concrete registry path, and the new text. */
    public record Edit(int slide, String path, String text) {
    }

    /** One changed editable string between two specs; {@code oldText}/{@code newText} are null when absent. */
    public record TextChange(int slide, String path, String oldText, String newText) {
    }

    /** An edit that cannot be applied (not editable, missing, not a string, out of range, too long). */
    public static class EditException extends IllegalArgumentException {
        public EditException(String message) {
            super(message);
        }
    }

    /** True when {@code path} is a concrete path whose index-free form is a registry pattern. */
    public static boolean isEditablePath(String path) {
        return path != null && CONCRETE_PATH.matcher(path).matches()
                && PATTERN_SET.contains(INDEX.matcher(path).replaceAll("[]"));
    }

    /** Every editable string of every slide, slides ascending, each slide's fields in registry order. */
    public static List<TextField> list(JsonNode spec) {
        List<TextField> out = new ArrayList<>();
        JsonNode slides = spec == null ? null : spec.get("slides");
        if (slides == null || !slides.isArray()) {
            return out;
        }
        for (int i = 0; i < slides.size(); i++) {
            JsonNode slide = slides.get(i);
            if (!slide.isObject()) {
                continue;
            }
            for (String pattern : PATTERNS) {
                collect(slide, tokens(pattern), 0, "", i + 1, out);
            }
        }
        return out;
    }

    /**
     * A copy of {@code spec} with each edit's string replaced. Throws {@link EditException} (before returning
     * anything) for a slide out of range, a path that is not in the registry, a path that is missing, a target
     * that is not already a string, or text longer than {@value #MAX_TEXT_LENGTH} characters.
     */
    public static JsonNode apply(JsonNode spec, List<Edit> edits) {
        JsonNode copy = spec.deepCopy();
        JsonNode slides = copy.get("slides");
        for (Edit edit : edits) {
            String where = "slide " + edit.slide() + " " + edit.path();
            if (slides == null || !slides.isArray() || edit.slide() < 1 || edit.slide() > slides.size()
                    || !slides.get(edit.slide() - 1).isObject()) {
                throw new EditException(where + ": there is no such slide");
            }
            if (!isEditablePath(edit.path())) {
                throw new EditException(where + ": not an editable text field");
            }
            if (edit.text() == null) {
                throw new EditException(where + ": the new text is required");
            }
            if (edit.text().length() > MAX_TEXT_LENGTH) {
                throw new EditException(where + ": the text is longer than " + MAX_TEXT_LENGTH + " characters");
            }
            JsonNode parent = slides.get(edit.slide() - 1);
            List<Object> steps = steps(edit.path());
            for (int s = 0; s < steps.size() - 1; s++) {
                parent = child(parent, steps.get(s));
                if (parent == null) {
                    throw new EditException(where + ": no such field on the slide");
                }
            }
            Object last = steps.get(steps.size() - 1);
            JsonNode target = child(parent, last);
            if (target == null) {
                throw new EditException(where + ": no such field on the slide");
            }
            if (!target.isTextual()) {
                throw new EditException(where + ": the field is not text");
            }
            if (last instanceof Integer idx) {
                ((ArrayNode) parent).set(idx, TextNode.valueOf(edit.text()));
            } else {
                ((ObjectNode) parent).set((String) last, TextNode.valueOf(edit.text()));
            }
        }
        return copy;
    }

    /**
     * The editable strings that differ between {@code a} and {@code b}, over the slides both have (slides
     * present in only one are not diffed). A path present in one spec only reports null for the other side.
     */
    public static List<TextChange> diff(JsonNode a, JsonNode b) {
        Map<Integer, Map<String, String>> before = bySlide(list(a));
        Map<Integer, Map<String, String>> after = bySlide(list(b));
        int common = Math.min(slideCount(a), slideCount(b));
        List<TextChange> out = new ArrayList<>();
        for (int slide = 1; slide <= common; slide++) {
            Map<String, String> x = before.getOrDefault(slide, Map.of());
            Map<String, String> y = after.getOrDefault(slide, Map.of());
            Set<String> paths = new LinkedHashSet<>(x.keySet());
            paths.addAll(y.keySet());
            for (String path : paths) {
                String o = x.get(path);
                String n = y.get(path);
                if (o == null ? n != null : !o.equals(n)) {
                    out.add(new TextChange(slide, path, o, n));
                }
            }
        }
        return out;
    }

    /** {@link #diff} as the stored {@code text_diff} JSON: {@code [{slide, path, old, new}]}. */
    public static ArrayNode diffJson(JsonNode a, JsonNode b) {
        ArrayNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (TextChange c : diff(a, b)) {
            ObjectNode n = out.addObject();
            n.put("slide", c.slide());
            n.put("path", c.path());
            n.put("old", c.oldText());
            n.put("new", c.newText());
        }
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────

    private static int slideCount(JsonNode spec) {
        JsonNode slides = spec == null ? null : spec.get("slides");
        return slides != null && slides.isArray() ? slides.size() : 0;
    }

    private static Map<Integer, Map<String, String>> bySlide(List<TextField> fields) {
        Map<Integer, Map<String, String>> out = new LinkedHashMap<>();
        for (TextField f : fields) {
            out.computeIfAbsent(f.slide(), k -> new LinkedHashMap<>()).put(f.path(), f.text());
        }
        return out;
    }

    /** A pattern's steps: a field name, or {@code null} for an "any index" step. */
    private static List<String> tokens(String pattern) {
        List<String> out = new ArrayList<>();
        for (String part : pattern.split("\\.")) {
            int bracket = part.indexOf('[');
            out.add(bracket < 0 ? part : part.substring(0, bracket));
            int rest = bracket;
            while (rest >= 0 && rest < part.length()) {
                out.add(null);
                rest = part.indexOf('[', rest + 1);
            }
        }
        return out;
    }

    private static void collect(JsonNode node, List<String> tokens, int at, String path, int slide, List<TextField> out) {
        if (at == tokens.size()) {
            if (node.isTextual()) {
                out.add(new TextField(slide, path, node.asText()));
            }
            return;
        }
        String token = tokens.get(at);
        if (token == null) {
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    collect(node.get(i), tokens, at + 1, path + "[" + i + "]", slide, out);
                }
            }
        } else if (node.isObject() && node.has(token)) {
            collect(node.get(token), tokens, at + 1, path.isEmpty() ? token : path + "." + token, slide, out);
        }
    }

    /** A concrete path as steps: a String field name or an Integer index. */
    private static List<Object> steps(String path) {
        List<Object> out = new ArrayList<>();
        var m = STEP.matcher(path);
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : (Object) Integer.valueOf(m.group(2)));
        }
        return out;
    }

    private static JsonNode child(JsonNode node, Object step) {
        if (step instanceof Integer i) {
            return node.isArray() && i < node.size() ? node.get(i) : null;
        }
        return node.isObject() ? node.get((String) step) : null;
    }
}
