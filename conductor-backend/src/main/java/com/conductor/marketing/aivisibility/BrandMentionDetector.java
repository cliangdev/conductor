package com.conductor.marketing.aivisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an assistant's answer names the brand, and where.
 *
 * <p><b>The rule:</b> a brand term counts when it appears as a whole word, in any case, with an optional plural
 * "s". Letters, digits and underscores are word characters, so {@code Rexipe}, {@code REXIPE}, {@code Rexipes},
 * {@code Rexipe's}, {@code (Rexipe)} and {@code rexipe.io} count, while {@code rexipeapp}, {@code myrexipe},
 * {@code Rexipe2} and {@code rexipe_app} do not. A term of several words matches across any run of whitespace.
 *
 * <p><b>Rank:</b> when the answer holds a list of at least two top-level items (numbered, else bulleted), the rank
 * is the 1-based position of the first item that names the brand. An item is its marker line plus the lines
 * wrapped or indented beneath it, up to the next blank line, so a closing paragraph after the list is never part
 * of the last item. The rank is null when the brand is not named, the answer has no list, or the only mentions sit
 * outside the list.
 *
 * <p><b>Excerpt:</b> whitespace is collapsed to single spaces; a named answer keeps {@value #CONTEXT_CHARS}
 * characters either side of the first mention, an unnamed one its first {@value #LEAD_CHARS}; a cut end gets
 * {@code "..."}.
 */
final class BrandMentionDetector {

    /** The longest brand term accepted; keeps an excerpt within {@link #MAX_EXCERPT_LENGTH}. */
    static final int MAX_TERM_LENGTH = 80;
    /** The excerpt column is 600 characters. */
    static final int MAX_EXCERPT_LENGTH = 600;

    static final int CONTEXT_CHARS = 200;
    static final int LEAD_CHARS = 300;
    private static final String ELLIPSIS = "...";

    private static final String WORD_CHARS = "[\\p{L}\\p{N}_]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** Optional heading hashes and bold, then a number with a dot or parenthesis (numbered) or a dash, star or bullet. */
    private static final Pattern MARKER =
            Pattern.compile("^([ \\t]*)(?:#{1,6}[ \\t]+)?(?:\\*\\*)?(?:(\\d{1,3}[.)])|([-*\\u2022]))[ \\t]+");

    record Detection(boolean named, Integer rank, String excerpt) {}

    private enum Kind { NUMBERED, BULLET }

    private record Marker(Kind kind, int indent) {}

    private BrandMentionDetector() {}

    /**
     * @throws IllegalArgumentException when the answer is blank or no term is usable: an answer that was not
     *         read, or terms that cannot match, must never be reported as "not named"
     */
    static Detection detect(String answer, List<String> brandTerms) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("an empty answer cannot be checked");
        }
        Pattern pattern = termPattern(brandTerms);
        String flat = collapse(answer);
        Matcher first = pattern.matcher(flat);
        if (!first.find()) {
            return new Detection(false, null, lead(flat));
        }
        return new Detection(true, rankIn(answer, pattern), around(flat, first.start(), first.end()));
    }

    private static Pattern termPattern(List<String> brandTerms) {
        List<String> alternatives = new ArrayList<>();
        for (String term : brandTerms == null ? List.<String>of() : brandTerms) {
            String trimmed = term == null ? "" : term.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> words = new ArrayList<>();
            for (String word : trimmed.split("\\s+")) {
                words.add(Pattern.quote(word));
            }
            alternatives.add(String.join("\\s+", words));
        }
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("at least one brand term is needed");
        }
        return Pattern.compile("(?<!" + WORD_CHARS + ")(?:" + String.join("|", alternatives) + ")s?(?!" + WORD_CHARS + ")",
                FLAGS);
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String lead(String flat) {
        return flat.length() <= LEAD_CHARS ? flat : flat.substring(0, LEAD_CHARS) + ELLIPSIS;
    }

    private static String around(String flat, int start, int end) {
        int from = Math.max(0, start - CONTEXT_CHARS);
        int to = Math.min(flat.length(), end + CONTEXT_CHARS);
        String excerpt = (from > 0 ? ELLIPSIS : "") + flat.substring(from, to) + (to < flat.length() ? ELLIPSIS : "");
        return excerpt.length() <= MAX_EXCERPT_LENGTH ? excerpt
                : excerpt.substring(0, MAX_EXCERPT_LENGTH - ELLIPSIS.length()) + ELLIPSIS;
    }

    // ---- rank ---------------------------------------------------------------------------------------------

    private static Integer rankIn(String answer, Pattern pattern) {
        List<String> lines = answer.lines().toList();
        List<Marker> markers = new ArrayList<>();
        for (String line : lines) {
            markers.add(marker(line));
        }
        Kind kind = listKind(markers);
        if (kind == null) {
            return null;
        }
        int topIndent = topIndent(markers, kind);
        List<String> items = items(lines, markers, kind, topIndent);
        for (int i = 0; i < items.size(); i++) {
            if (pattern.matcher(items.get(i)).find()) {
                return i + 1;
            }
        }
        return null;
    }

    private static Marker marker(String line) {
        Matcher m = MARKER.matcher(line);
        if (!m.find()) {
            return null;
        }
        int indent = m.group(1).replace("\t", "    ").length();
        return new Marker(m.group(2) != null ? Kind.NUMBERED : Kind.BULLET, indent);
    }

    /** Numbered when it has two or more top-level items, else bulleted when it does, else no list. */
    private static Kind listKind(List<Marker> markers) {
        for (Kind kind : Kind.values()) {
            int top = topIndent(markers, kind);
            if (top >= 0 && markers.stream().filter(m -> m != null && m.kind() == kind && m.indent() == top).count() >= 2) {
                return kind;
            }
        }
        return null;
    }

    /** The smallest indent among markers of {@code kind}, or -1 when there are none. */
    private static int topIndent(List<Marker> markers, Kind kind) {
        return markers.stream().filter(m -> m != null && m.kind() == kind).mapToInt(Marker::indent).min().orElse(-1);
    }

    /** Each top-level item's text: its marker line and the non-blank lines beneath it that do not start a new item. */
    private static List<String> items(List<String> lines, List<Marker> markers, Kind kind, int topIndent) {
        List<String> items = new ArrayList<>();
        StringBuilder current = null;
        for (int i = 0; i < lines.size(); i++) {
            Marker marker = markers.get(i);
            boolean startsItem = marker != null && marker.kind() == kind && marker.indent() == topIndent;
            boolean endsItem = lines.get(i).isBlank() || (marker != null && marker.indent() <= topIndent);
            if (startsItem) {
                if (current != null) {
                    items.add(current.toString());
                }
                current = new StringBuilder(lines.get(i));
            } else if (endsItem) {
                if (current != null) {
                    items.add(current.toString());
                    current = null;
                }
            } else if (current != null) {
                current.append('\n').append(lines.get(i));
            }
        }
        if (current != null) {
            items.add(current.toString());
        }
        return items;
    }
}
