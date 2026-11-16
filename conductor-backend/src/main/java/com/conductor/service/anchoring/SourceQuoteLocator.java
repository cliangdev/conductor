package com.conductor.service.anchoring;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.Range;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the span of a document's <em>source</em> (markdown or HTML) that a text-quote anchor, written against the
 * rendered text, stands for, so a suggested edit can replace it. Deliberately strict: it answers only when the
 * quote is one contiguous piece of source text in exactly one place.
 *
 * <ul>
 *   <li>The quote's whitespace is normalised and may match any whitespace run in the source (a wrapped line).</li>
 *   <li>Markdown: the quote must be plain source text. Emphasis, links, code spans or entities inside the quote
 *       break the match, so it is refused; so is a match across a blank line.</li>
 *   <li>HTML: the quote must sit inside one text node. A span crossing an element boundary is refused. Entities
 *       in the source ({@code &amp;}) are decoded for matching and kept as source text around the replacement.</li>
 *   <li>Several candidates: the occurrence the reader selected is the one {@link QuoteMatcher} picks in the
 *       rendered text (prefix/suffix scoring); it maps to the same-numbered source occurrence, but only when
 *       the source and the rendered text have the same number of occurrences. Otherwise it is ambiguous.</li>
 * </ul>
 */
public final class SourceQuoteLocator {

    /** One reader-facing message for every refusal: the reader can't act on the difference. */
    public static final String AMBIGUOUS =
            "This text appears more than once or spans formatting — comment instead";
    public static final String NOT_FOUND = AMBIGUOUS;

    /** A located span {@code [start,end)} of the source, or a refusal with a reader-facing reason. */
    public record Result(int start, int end, String reason) {
        public boolean found() {
            return reason == null;
        }

        static Result ok(int start, int end) {
            return new Result(start, end, null);
        }

        static Result no(String reason) {
            return new Result(-1, -1, reason);
        }
    }

    private static final Set<String> SKIP = Set.of("script", "style", "noscript", "template");
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t\\r]*\\n");
    private static final String WS = "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+";

    private SourceQuoteLocator() {
    }

    public static Result locate(String source, boolean html, String exact, String prefix, String suffix) {
        String q = QuoteMatcher.normalize(exact);
        if (source == null || q.isEmpty()) {
            return Result.no(NOT_FOUND);
        }
        String projected = TextProjection.project(source, html);
        int rendered = count(projected, q);
        if (rendered == 0) {
            return Result.no(NOT_FOUND);
        }
        QuoteMatcher.Match chosen = QuoteMatcher.find(projected, exact, prefix, suffix);
        if (chosen == null) {
            return Result.no(NOT_FOUND);
        }
        int rank = count(projected.substring(0, chosen.start()), q);

        List<int[]> candidates = html ? htmlCandidates(source, q) : markdownCandidates(source, q);
        if (candidates.isEmpty()) {
            return Result.no(NOT_FOUND);
        }
        if (candidates.size() != rendered || rank >= candidates.size()) {
            return Result.no(AMBIGUOUS);
        }
        int[] span = candidates.get(rank);
        return Result.ok(span[0], span[1]);
    }

    /** The source with the span replaced; the replacement is escaped as HTML text when the source is HTML. */
    public static String apply(String source, Result span, String replacement, boolean html) {
        String text = replacement == null ? "" : replacement;
        if (html) {
            text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
        return source.substring(0, span.start()) + text + source.substring(span.end());
    }

    private static int count(String text, String q) {
        int n = 0;
        for (int i = text.indexOf(q); i >= 0; i = text.indexOf(q, i + 1)) {
            n++;
        }
        return n;
    }

    /** Whitespace-tolerant pattern for an already-normalised quote. */
    private static Pattern pattern(String q) {
        StringBuilder b = new StringBuilder();
        for (String token : q.split(" ")) {
            if (b.length() > 0) {
                b.append(WS);
            }
            b.append(Pattern.quote(token));
        }
        return Pattern.compile(b.toString());
    }

    private static List<int[]> markdownCandidates(String source, String q) {
        List<int[]> out = new ArrayList<>();
        Matcher m = pattern(q).matcher(source);
        int from = 0;
        while (m.find(from)) {
            if (!BLANK_LINE.matcher(m.group()).find()) {
                out.add(new int[]{m.start(), m.end()});
            }
            from = m.start() + 1;
        }
        return out;
    }

    private static List<int[]> htmlCandidates(String source, String q) {
        Document doc = Jsoup.parse(source, Parser.htmlParser().setTrackPosition(true));
        Element root = doc.body() != null ? doc.body() : doc;
        List<int[]> out = new ArrayList<>();
        Pattern p = pattern(q);
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (!(node instanceof TextNode text) || !(node.parent() instanceof Element parent)
                        || skipped(parent, root)) {
                    return;
                }
                Range range = text.sourceRange();
                if (!range.isTracked()) {
                    return;
                }
                String decoded = text.getWholeText();
                Matcher m = p.matcher(decoded);
                if (!m.find()) {
                    return;
                }
                int base = range.start().pos();
                String raw = source.substring(base, range.end().pos());
                int[] map = rawIndexOfDecoded(raw, decoded);
                if (map == null) {
                    return; // source text we cannot map back reliably: left out, which reads as ambiguous
                }
                int from = 0;
                while (m.find(from)) {
                    out.add(new int[]{base + map[m.start()], base + map[m.end()]});
                    from = m.start() + 1;
                }
            }

            @Override
            public void tail(Node node, int depth) {
            }
        }, root);
        return out;
    }

    private static boolean skipped(Element el, Element root) {
        for (Element e = el; e != null; e = e.parent()) {
            if (SKIP.contains(e.normalName()) || e.hasAttr("hidden")) {
                return true;
            }
            if (e == root) {
                break;
            }
        }
        return false;
    }

    /**
     * For a text node's raw source and its decoded text, the raw index of each decoded index (length + 1, the last
     * being the raw end). Null when the two cannot be aligned, e.g. a parser-normalised newline.
     */
    static int[] rawIndexOfDecoded(String raw, String decoded) {
        int[] map = new int[decoded.length() + 1];
        int r = 0;
        int d = 0;
        while (d < decoded.length()) {
            if (r >= raw.length()) {
                return null;
            }
            char c = raw.charAt(r);
            if (c == '&') {
                Matcher m = ENTITY.matcher(raw);
                m.region(r, raw.length());
                if (m.lookingAt()) {
                    String value = Parser.unescapeEntities(m.group(), false);
                    if (!value.equals(m.group()) && decoded.startsWith(value, d)) {
                        for (int k = 0; k < value.length(); k++) {
                            map[d + k] = r;
                        }
                        d += value.length();
                        r = m.end();
                        continue;
                    }
                }
            }
            if (c != decoded.charAt(d)) {
                return null;
            }
            map[d] = r;
            d++;
            r++;
        }
        map[decoded.length()] = r;
        return r == raw.length() ? map : null;
    }
}
