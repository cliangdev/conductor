package com.conductor.service.anchoring;

import com.conductor.entity.Comment;
import com.conductor.repository.CommentRepository;
import com.conductor.service.CommentTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentAnchorServiceTest {

    private static final CommentTarget.WorkItemDocument WI = new CommentTarget.WorkItemDocument("wi-1", "doc-1");
    private static final CommentTarget.ProjectDoc PD = new CommentTarget.ProjectDoc("pd-1");

    @Mock CommentRepository repository;
    @InjectMocks CommentAnchorService service;

    private void open(CommentTarget.WorkItemDocument t, Comment... comments) {
        when(repository.findAllByDocumentIdAndResolvedAtIsNull(t.documentId())).thenReturn(List.of(comments));
    }

    private static Comment quote(String exact, String prefix, String suffix) {
        Comment c = new Comment();
        c.setAnchorExact(exact);
        c.setAnchorPrefix(prefix);
        c.setAnchorSuffix(suffix);
        c.setQuotedText(exact);
        c.setDocumentHash("old");
        return c;
    }

    private static Comment line(int number, String text) {
        Comment c = new Comment();
        c.setLineNumber(number);
        c.setQuotedText(text);
        c.setDocumentHash("old");
        return c;
    }

    // --- quote comments -----------------------------------------------------------------------------

    @Test
    void aFoundQuoteRefreshesItsContextAndHashAndIsAnchored() {
        Comment c = quote("20 of 26", "stale prefix", "stale suffix");
        open(WI, c);

        service.reanchor(WI, "<p>Total: 20 of 26 users</p>", "new", "text/html");

        assertThat(c.getAnchorStatus()).isEqualTo("ANCHORED");
        assertThat(c.getAnchorPrefix()).isEqualTo("Total: ");
        assertThat(c.getAnchorSuffix()).isEqualTo(" users");
        assertThat(c.getDocumentHash()).isEqualTo("new");
        assertThat(c.getAnchorExact()).isEqualTo("20 of 26");
    }

    @Test
    void aMissingQuoteIsOrphanedAndKeepsItsHash() {
        Comment c = quote("20 of 26", "Total: ", " users");
        c.setAnchorStatus("ANCHORED");
        open(WI, c);

        service.reanchor(WI, "<p>Rewritten.</p>", "new", "text/html");

        assertThat(c.getAnchorStatus()).isEqualTo("ORPHANED");
        assertThat(c.getDocumentHash()).isEqualTo("old");
        assertThat(c.getAnchorPrefix()).isEqualTo("Total: ");
    }

    @Test
    void anOrphanedQuoteThatReturnsIsAnchoredAgain() {
        Comment c = quote("back again", "", "");
        c.setAnchorStatus("ORPHANED");
        open(WI, c);

        service.reanchor(WI, "# Title\n\nback again", "new", "text/markdown");

        assertThat(c.getAnchorStatus()).isEqualTo("ANCHORED");
        assertThat(c.getDocumentHash()).isEqualTo("new");
    }

    @Test
    void markdownQuotesAreMatchedAgainstRenderedText() {
        Comment c = quote("really need the launch plan", "We ", "");
        open(WI, c);

        service.reanchor(WI, "We **really** need the [launch plan](https://x.test).", "new", "text/markdown");

        assertThat(c.getAnchorStatus()).isEqualTo("ANCHORED");
    }

    // --- line comments ------------------------------------------------------------------------------

    @Test
    void aLineWhoseTextIsStillThereIsKeptAndRestamped() {
        Comment c = line(2, "  second  ");
        c.setLineStale(true);
        open(WI, c);

        service.reanchor(WI, "first\nsecond\nthird", "new", "text/markdown");

        assertThat(c.getLineNumber()).isEqualTo(2);
        assertThat(c.isLineStale()).isFalse();
        assertThat(c.getDocumentHash()).isEqualTo("new");
    }

    @Test
    void aMovedLineFollowsItsTextWhenItIsOnExactlyOneLine() {
        Comment c = line(2, "second");
        open(WI, c);

        service.reanchor(WI, "zero\nfirst\nx\nsecond\nthird", "new", "text/markdown");

        assertThat(c.getLineNumber()).isEqualTo(4);
        assertThat(c.isLineStale()).isFalse();
        assertThat(c.getDocumentHash()).isEqualTo("new");
    }

    @Test
    void aLineWhoseTextIsOnSeveralOtherLinesIsStale() {
        Comment c = line(1, "dup");
        open(WI, c);

        service.reanchor(WI, "a\ndup\ndup", "new", "text/markdown");

        assertThat(c.getLineNumber()).isEqualTo(1);
        assertThat(c.isLineStale()).isTrue();
        assertThat(c.getDocumentHash()).isEqualTo("old");
    }

    @Test
    void aLineWhoseTextIsGoneIsStale() {
        Comment c = line(3, "deleted");
        open(WI, c);

        service.reanchor(WI, "a\nb\nc", "new", "text/markdown");

        assertThat(c.isLineStale()).isTrue();
        assertThat(c.getDocumentHash()).isEqualTo("old");
    }

    @Test
    void aDuplicatedLineStillAtItsOwnNumberIsKept() {
        Comment c = line(3, "dup");
        open(WI, c);

        service.reanchor(WI, "a\ndup\ndup", "new", "text/markdown");

        assertThat(c.getLineNumber()).isEqualTo(3);
        assertThat(c.isLineStale()).isFalse();
    }

    @Test
    void aLineWithNoStoredTextIsKeptWhileItsNumberExists() {
        Comment inRange = line(2, "");
        Comment past = line(9, null);
        open(WI, inRange, past);

        service.reanchor(WI, "a\nb\nc", "new", "text/markdown");

        assertThat(inRange.isLineStale()).isFalse();
        assertThat(past.isLineStale()).isTrue();
    }

    // --- whole document, targets, plumbing -----------------------------------------------------------

    @Test
    void aWholeDocumentCommentIsRestamped() {
        Comment c = new Comment();
        c.setDocumentHash("old");
        open(WI, c);

        service.reanchor(WI, "anything", "new", "text/markdown");

        assertThat(c.getDocumentHash()).isEqualTo("new");
        assertThat(c.getAnchorStatus()).isNull();
    }

    @Test
    void projectDocsAreLoadedByTheirOwnQueryAndAreMarkdown() {
        Comment q = quote("beta", "", "");
        Comment l = line(1, "# Title");
        when(repository.findAllByProjectDocIdAndResolvedAtIsNull("pd-1")).thenReturn(List.of(q, l));

        service.reanchor(PD, "# Title\n\nalpha *beta*", "new", "text/markdown");

        assertThat(q.getAnchorStatus()).isEqualTo("ANCHORED");
        assertThat(l.isLineStale()).isFalse();
        verify(repository, never()).findAllByDocumentIdAndResolvedAtIsNull(any());
    }

    @Test
    void nothingIsSavedWhenThereAreNoOpenComments() {
        open(WI);

        service.reanchor(WI, "text", "new", "text/markdown");

        verify(repository, never()).saveAll(any());
    }

    @Test
    void nullContentDoesNothing() {
        service.reanchor(WI, null, "new", "text/markdown");

        verify(repository, never()).findAllByDocumentIdAndResolvedAtIsNull(any());
    }

    @Test
    void theChangedCommentsAreSavedTogether() {
        Comment a = quote("x", "", "");
        Comment b = line(1, "y");
        open(WI, a, b);

        service.reanchor(WI, "x\ny", "new", "text/markdown");

        verify(repository).saveAll(List.of(a, b));
    }
}
