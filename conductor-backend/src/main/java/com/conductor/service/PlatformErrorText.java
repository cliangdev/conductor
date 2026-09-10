package com.conductor.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a platform's own error text into what a person reads and what a debugger reads.
 *
 * <p>A connector's error message today is whatever the platform handed back, wholesale: an HTTP status
 * code, a JSON error body, sometimes a trace id, prefixed by a sentence naming what Conductor was trying
 * to do — {@code "Facebook could not read post 122137176783347721: 400 {\"error\":{...}}"}. That whole
 * string lands in {@code post_publish_target.error_message} and {@code connection.health_message} and the
 * UI shows it verbatim, which is unreadable to anyone who isn't the engineer who wrote the connector.
 *
 * <p>{@link #humanize} keeps only the human sentence at the front — capitalized platform name, plain
 * words, a full stop — for the message column; {@link #detail} keeps everything, for a debug affordance
 * next to it. Neither ever throws; a shape this does not recognize is returned as-is (humanized, minus an
 * HTTP/JSON suffix it can still find) rather than blanked out.
 */
public final class PlatformErrorText {

    /** Where a human sentence is capped; long past what any platform's own error prose runs to. */
    public static final int HUMANIZE_MAX_LENGTH = 200;

    // "<sentence>: 400 {...}" or "<sentence> 400 {...}" — an HTTP status code immediately followed by a
    // JSON (or other bracketed) body. Reluctant group 1 so it stops at the first such suffix.
    private static final Pattern STATUS_AND_BODY =
            Pattern.compile("^(.*?)\\s*:?\\s+\\d{3}\\s*[{\\[].*$", Pattern.DOTALL);

    // A trace/log id parenthetical or trailing clause a platform tacks on, e.g. "(fbtrace_id: Abc123)" or
    // "log_id=deadbeef" — noise once the JSON body it usually sits beside is already gone.
    private static final Pattern TRACE_ID =
            Pattern.compile("\\(?\\b(?:fbtrace_id|trace[_-]?id|log[_-]?id|request[_-]?id)\\b\\s*[:=]\\s*[^\\s)]+\\)?",
                    Pattern.CASE_INSENSITIVE);

    private PlatformErrorText() {
    }

    /**
     * The first human sentence of a platform error, with any JSON body, HTTP status prefix and trace id
     * stripped, capped at {@link #HUMANIZE_MAX_LENGTH} characters. Null and blank pass through as null.
     */
    public static String humanize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();

        Matcher statusAndBody = STATUS_AND_BODY.matcher(text);
        if (statusAndBody.matches()) {
            text = statusAndBody.group(1).trim();
        }
        text = TRACE_ID.matcher(text).replaceAll("").trim();
        text = firstSentence(text);
        text = text.replaceAll("\\s+", " ").trim();
        text = stripTrailingPunctuation(text);
        if (text.isEmpty()) {
            return null;
        }
        text = text + ".";
        return cap(text, HUMANIZE_MAX_LENGTH);
    }

    /** The full original error text, trimmed but otherwise untouched, for a debug affordance. Null-safe. */
    public static String detail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstSentence(String text) {
        int cut = -1;
        for (String delimiter : new String[]{". ", "! ", "? ", "\n"}) {
            int idx = text.indexOf(delimiter);
            if (idx >= 0 && (cut == -1 || idx < cut)) {
                cut = idx;
            }
        }
        return cut >= 0 ? text.substring(0, cut + 1) : text;
    }

    private static String stripTrailingPunctuation(String text) {
        String stripped = text;
        while (!stripped.isEmpty() && ":;,. -".indexOf(stripped.charAt(stripped.length() - 1)) >= 0) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static String cap(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).trim() + "…";
    }
}
