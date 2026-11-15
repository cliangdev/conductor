package com.conductor.service.anchoring;

/**
 * Finds a text-quote anchor in projected text: a Java port of {@code findQuote} in the frontend's
 * {@code htmlBridge.ts}, scored identically so the server and the browser agree on which match a comment is on.
 * Among all exact matches the one whose surrounding text agrees most with the stored prefix and suffix wins
 * (the length of the common run immediately before plus immediately after); ties go to the earliest.
 */
public final class QuoteMatcher {

    /** Characters of context kept either side of a quote, as {@code buildQuote} does in the browser. */
    public static final int CONTEXT = 32;

    public record Match(int start, int end) {
    }

    /** A quote's context, refreshed from the new text. */
    public record Context(String prefix, String suffix) {
    }

    private QuoteMatcher() {
    }

    /** Whitespace runs to one space (JavaScript {@code \s}), the same normalisation the browser applies. */
    static String collapse(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean inSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (TextProjection.isSpace(c)) {
                if (!inSpace) {
                    b.append(' ');
                }
                inSpace = true;
            } else {
                b.append(c);
                inSpace = false;
            }
        }
        return b.toString();
    }

    /** {@code collapse} then trim (JavaScript's {@code trim()} strips the same set). */
    static String normalize(String s) {
        String c = collapse(s);
        int a = 0;
        int e = c.length();
        while (a < e && TextProjection.isSpace(c.charAt(a))) a++;
        while (e > a && TextProjection.isSpace(c.charAt(e - 1))) e--;
        return c.substring(a, e);
    }

    /** The match for the quote in {@code text}, or null when its exact text is not there. */
    public static Match find(String text, String exactRaw, String prefixRaw, String suffixRaw) {
        String exact = normalize(exactRaw);
        if (exact.isEmpty()) {
            return null;
        }
        String prefix = collapse(prefixRaw);
        String suffix = collapse(suffixRaw);
        int best = -1;
        int bestScore = -1;
        int from = 0;
        for (;;) {
            int i = text.indexOf(exact, from);
            if (i < 0) {
                break;
            }
            from = i + 1;
            int score = 0;
            String before = text.substring(Math.max(0, i - prefix.length()), i);
            for (int k = 1; k <= before.length() && k <= prefix.length(); k++) {
                if (before.charAt(before.length() - k) == prefix.charAt(prefix.length() - k)) {
                    score++;
                } else {
                    break;
                }
            }
            int e = i + exact.length();
            String after = text.substring(e, Math.min(text.length(), e + suffix.length()));
            for (int k = 0; k < after.length() && k < suffix.length(); k++) {
                if (after.charAt(k) == suffix.charAt(k)) {
                    score++;
                } else {
                    break;
                }
            }
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best < 0 ? null : new Match(best, best + exact.length());
    }

    /** Up to {@link #CONTEXT} characters either side of the match, as {@code buildQuote} takes them. */
    public static Context contextOf(String text, Match m) {
        return new Context(
                collapse(text.substring(Math.max(0, m.start() - CONTEXT), m.start())),
                collapse(text.substring(m.end(), Math.min(text.length(), m.end() + CONTEXT))));
    }
}
