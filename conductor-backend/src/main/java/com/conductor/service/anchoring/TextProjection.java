package com.conductor.service.anchoring;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A document's visible text, flattened exactly as the frontend's comment bridge flattens a rendered page
 * (the {@code flatten} function inside {@code bridgeMain} in {@code htmlBridge.ts}), so quote anchors made in the
 * browser can be found again on the server.
 *
 * <p>Rules, all mirrored from the bridge: only text nodes count; text inside script, style, noscript, template
 * or a {@code [hidden]} element is dropped; every whitespace run collapses to one space (JavaScript's {@code \s}
 * set, including no-break space); leading whitespace is dropped; and one space is put wherever the nearest block
 * ancestor changes or a {@code <br>} intervenes. Markdown is rendered to HTML with commonmark first.
 *
 * <p>What the server cannot see is CSS: text hidden by a stylesheet or {@code display:none} is still projected,
 * where the browser skips it. That only matters for a quote that exists solely in such hidden text.
 */
public final class TextProjection {

    private static final Set<String> SKIP = Set.of("script", "style", "noscript", "template");
    private static final Set<String> BLOCKS = Set.of(
            "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt", "figcaption", "figure", "footer",
            "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre",
            "section", "table", "tbody", "td", "th", "thead", "tr", "ul");

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create());
    private static final Parser MARKDOWN = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).build();

    private TextProjection() {
    }

    /** True for an HTML content type ({@code text/html}); anything else is treated as markdown. */
    public static boolean isHtml(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("html");
    }

    public static String project(String content, boolean html) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return projectHtml(html ? content : RENDERER.render(MARKDOWN.parse(content)));
    }

    public static String project(String content, String contentType) {
        return project(content, isHtml(contentType));
    }

    static String projectHtml(String html) {
        Document doc = Jsoup.parse(html);
        Element root = doc.body() != null ? doc.body() : doc;
        Flattener f = new Flattener(root);
        NodeTraversor.traverse(f, root);
        return f.out.toString();
    }

    /** JavaScript's {@code /\s/}: the Unicode space separators, line terminators, tab, VT, FF and BOM. */
    static boolean isSpace(char c) {
        switch (c) {
            case '\t', '\n', 0x0B, '\f', '\r', ' ', 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF:
                return true;
            default:
                return c >= 0x2000 && c <= 0x200A;
        }
    }

    private static final class Flattener implements NodeVisitor {
        final StringBuilder out = new StringBuilder();
        private final Element root;
        private boolean lastSpace = true;
        private boolean pendingBreak = false;
        private Element prevBlock = null;

        Flattener(Element root) {
            this.root = root;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof Element el) {
                if (el.normalName().equals("br")) {
                    pendingBreak = true;
                }
                return;
            }
            if (!(node instanceof TextNode text) || !(node.parent() instanceof Element parent)) {
                return;
            }
            if (isSkipped(parent)) {
                return;
            }
            Element block = closestBlock(parent);
            if (prevBlock != null && block != prevBlock) {
                pendingBreak = true;
            }
            prevBlock = block;
            if (pendingBreak && !lastSpace) {
                out.append(' ');
                lastSpace = true;
            }
            pendingBreak = false;
            String s = text.getWholeText();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (isSpace(c)) {
                    if (lastSpace) {
                        continue;
                    }
                    out.append(' ');
                    lastSpace = true;
                } else {
                    out.append(c);
                    lastSpace = false;
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
        }

        private boolean isSkipped(Element el) {
            for (Element e = el; e != null; e = e.parent()) {
                if (SKIP.contains(e.normalName()) || e.hasAttr("hidden") || e.hasAttr("data-conductor-skip")) {
                    return true;
                }
                if (e == root) {
                    break;
                }
            }
            return false;
        }

        private Element closestBlock(Element el) {
            for (Element e = el; e != null; e = e.parent()) {
                if (BLOCKS.contains(e.normalName())) {
                    return e;
                }
                if (e == root) {
                    break;
                }
            }
            return root;
        }
    }
}
