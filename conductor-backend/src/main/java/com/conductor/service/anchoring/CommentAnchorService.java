package com.conductor.service.anchoring;

import com.conductor.entity.Comment;
import com.conductor.repository.CommentRepository;
import com.conductor.service.CommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-finds every unresolved comment's anchor in a document's new content, on each save, so comments follow their
 * text instead of going stale. See {@code docs/comments.md}.
 *
 * <ul>
 *   <li>Quote comment: found again, its prefix/suffix and hash are refreshed and it is {@code ANCHORED};
 *       not found, it is {@code ORPHANED} and keeps its hash (and so reads as outdated).</li>
 *   <li>Line comment: stays when its stored line text is still on its line; moves when that exact (trimmed)
 *       text is on exactly one other line; otherwise it is flagged {@code line_stale}.</li>
 *   <li>Whole-document comment: the hash is re-stamped.</li>
 * </ul>
 * Resolved comments are history and are left alone.
 */
@Service
public class CommentAnchorService {

    private final CommentRepository commentRepository;

    public CommentAnchorService(CommentRepository commentRepository) {
        this.commentRepository = commentRepository;
    }

    /**
     * @param contentType the saved content's type; {@code text/html} projects as HTML, anything else as markdown
     */
    @Transactional
    public void reanchor(CommentTarget target, String newContent, String newHash, String contentType) {
        if (newContent == null) {
            return;
        }
        List<Comment> comments = switch (target) {
            case CommentTarget.WorkItemDocument d ->
                    commentRepository.findAllByDocumentIdAndResolvedAtIsNull(d.documentId());
            case CommentTarget.ProjectDoc d -> commentRepository.findAllByProjectDocIdAndResolvedAtIsNull(d.docId());
        };
        if (comments.isEmpty()) {
            return;
        }

        String text = null; // projected once, and only if a quote comment needs it
        Lines lines = null; // likewise for line comments
        for (Comment c : comments) {
            if (c.getAnchorExact() != null) {
                if (text == null) {
                    text = TextProjection.project(newContent, contentType);
                }
                reanchorQuote(c, text, newHash);
            } else if (c.getLineNumber() > 0) {
                if (lines == null) {
                    lines = new Lines(newContent);
                }
                reanchorLine(c, lines, newHash);
            } else {
                c.setDocumentHash(newHash);
            }
        }
        commentRepository.saveAll(comments);
    }

    private static void reanchorQuote(Comment c, String text, String newHash) {
        QuoteMatcher.Match m = QuoteMatcher.find(text, c.getAnchorExact(), c.getAnchorPrefix(), c.getAnchorSuffix());
        if (m == null) {
            c.setAnchorStatus("ORPHANED");
            return;
        }
        QuoteMatcher.Context ctx = QuoteMatcher.contextOf(text, m);
        c.setAnchorPrefix(ctx.prefix());
        c.setAnchorSuffix(ctx.suffix());
        c.setDocumentHash(newHash);
        c.setAnchorStatus("ANCHORED");
    }

    private static void reanchorLine(Comment c, Lines lines, String newHash) {
        String wanted = c.getQuotedText() == null ? "" : c.getQuotedText().trim();
        boolean kept;
        if (wanted.isEmpty()) {
            // Nothing to compare (a blank line): all we can say is whether the line number still exists.
            kept = c.getLineNumber() <= lines.count();
        } else if (wanted.equals(lines.trimmedAt(c.getLineNumber()))) {
            kept = true;
        } else {
            List<Integer> at = lines.linesWith(wanted);
            kept = at.size() == 1;
            if (kept) {
                c.setLineNumber(at.get(0));
            }
        }
        if (kept) {
            c.setLineStale(false);
            c.setDocumentHash(newHash);
        } else {
            c.setLineStale(true);
        }
    }

    /** The content's lines (1-based), with an index from trimmed text to line numbers built on first use. */
    private static final class Lines {
        private final String[] lines;
        private Map<String, List<Integer>> index;

        Lines(String content) {
            this.lines = content.split("\n", -1);
        }

        int count() {
            return lines.length;
        }

        String trimmedAt(int line) {
            return line >= 1 && line <= lines.length ? lines[line - 1].trim() : null;
        }

        List<Integer> linesWith(String trimmed) {
            if (index == null) {
                index = new HashMap<>();
                for (int i = 0; i < lines.length; i++) {
                    index.computeIfAbsent(lines[i].trim(), k -> new ArrayList<>(1)).add(i + 1);
                }
            }
            return index.getOrDefault(trimmed, List.of());
        }
    }
}
