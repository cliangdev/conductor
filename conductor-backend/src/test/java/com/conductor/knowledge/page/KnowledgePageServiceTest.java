package com.conductor.knowledge.page;

import com.conductor.exception.BusinessException;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link KnowledgePageService} — no Spring context, no DB. Covers path
 * normalization/reserved-path rejection and {@code batchWrite}'s optimistic-concurrency conflict
 * assembly (the parts that don't require a real database).
 */
class KnowledgePageServiceTest {

    private static final String PROJECT_ID = "proj-1";

    private KnowledgePageRepository pageRepository;
    private KnowledgePageRevisionRepository revisionRepository;
    private KnowledgeLinkRepository linkRepository;
    private KnowledgeSourceRepository sourceRepository;
    private KnowledgePageService service;

    @BeforeEach
    void setUp() {
        pageRepository = mock(KnowledgePageRepository.class);
        revisionRepository = mock(KnowledgePageRevisionRepository.class);
        linkRepository = mock(KnowledgeLinkRepository.class);
        sourceRepository = mock(KnowledgeSourceRepository.class);
        service = new KnowledgePageService(pageRepository, revisionRepository, linkRepository, sourceRepository,
                new FrontmatterParser(), new ObjectMapper());

        when(pageRepository.save(any(KnowledgePage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(revisionRepository.save(any(KnowledgePageRevision.class))).thenAnswer(inv -> {
            KnowledgePageRevision r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId("rev-" + r.getVersion());
            }
            return r;
        });
    }

    // --- path normalization ---

    @Test
    void normalizesTrimsLeadingSlashAndLowercases() {
        assertThat(service.normalizePath("  /Docs/Runbook.md  ")).isEqualTo("docs/runbook.md");
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.md", "log.md", "/index.md"})
    void rejectsReservedPaths(String path) {
        assertThatThrownBy(() -> service.normalizePath(path)).isInstanceOf(BusinessException.class);
    }

    @Test
    void allowsUnderscorePrefixedPaths() {
        assertThat(service.normalizePath("_schema.md")).isEqualTo("_schema.md");
        assertThat(service.normalizePath("_lint/rule.md")).isEqualTo("_lint/rule.md");
    }

    @Test
    void rejectsPathsWithDotDot() {
        assertThatThrownBy(() -> service.normalizePath("../escape.md")).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPathsNotEndingInMd() {
        assertThatThrownBy(() -> service.normalizePath("docs/runbook.txt")).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPathsWithInvalidCharacters() {
        assertThatThrownBy(() -> service.normalizePath("docs/Ru nbook!.md")).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsNullPath() {
        assertThatThrownBy(() -> service.normalizePath(null)).isInstanceOf(BusinessException.class);
    }

    // --- batchWrite conflict assembly ---

    private static final String DOC = """
            ---
            type: note
            title: A Note
            ---

            Body text.
            """;

    @Test
    void createWithNoBaseVersionSucceedsWhenPathIsFree() {
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.empty());

        List<PageWriteResult> results = service.batchWrite(PROJECT_ID,
                List.of(new PageWrite("notes/a.md", DOC, null, false)), List.of(), new Actor("user", "u1", null));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).path()).isEqualTo("notes/a.md");
        assertThat(results.get(0).version()).isEqualTo(1);
        verify(pageRepository).save(any(KnowledgePage.class));
    }

    @Test
    void duplicatePathsInOneBatchAreRejected() {
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.empty());

        List<PageWrite> writes = List.of(
                new PageWrite("notes/a.md", DOC, null, false),
                new PageWrite("/Notes/A.md", DOC, null, false));

        assertThatThrownBy(() -> service.batchWrite(PROJECT_ID, writes, List.of(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate path in batch: notes/a.md");
        verify(pageRepository, never()).save(any(KnowledgePage.class));
    }

    @Test
    void createWithBaseVersionOnNonexistentPageIsConflict() {
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.empty());

        PageWrite write = new PageWrite("notes/a.md", DOC, 3, false);

        assertThatThrownBy(() -> service.batchWrite(PROJECT_ID, List.of(write), List.of(), null))
                .isInstanceOfSatisfying(KnowledgeConflictException.class, ex -> {
                    assertThat(ex.conflicts()).hasSize(1);
                    KnowledgeConflictException.Conflict conflict = ex.conflicts().get(0);
                    assertThat(conflict.path()).isEqualTo("notes/a.md");
                    assertThat(conflict.currentVersion()).isZero();
                    assertThat(conflict.currentContent()).isNull();
                });
        verify(pageRepository, never()).save(any());
    }

    @Test
    void updateWithStaleBaseVersionIsConflictWithCurrentContent() {
        KnowledgePage existing = existingPage("notes/a.md", 2);
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.of(existing));

        PageWrite write = new PageWrite("notes/a.md", DOC, 1, false);

        assertThatThrownBy(() -> service.batchWrite(PROJECT_ID, List.of(write), List.of(), null))
                .isInstanceOfSatisfying(KnowledgeConflictException.class, ex -> {
                    KnowledgeConflictException.Conflict conflict = ex.conflicts().get(0);
                    assertThat(conflict.currentVersion()).isEqualTo(2);
                    assertThat(conflict.currentContent()).contains("Existing body");
                });
        verify(pageRepository, never()).save(any());
    }

    @Test
    void updateWithMatchingBaseVersionSucceeds() {
        KnowledgePage existing = existingPage("notes/a.md", 2);
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.of(existing));

        List<PageWriteResult> results = service.batchWrite(PROJECT_ID,
                List.of(new PageWrite("notes/a.md", DOC, 2, false)), List.of(), null);

        assertThat(results.get(0).version()).isEqualTo(3);
    }

    @Test
    void deleteRequiresMatchingBaseVersion() {
        KnowledgePage existing = existingPage("notes/a.md", 2);
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.of(existing));

        PageWrite badDelete = new PageWrite("notes/a.md", null, 1, true);
        assertThatThrownBy(() -> service.batchWrite(PROJECT_ID, List.of(badDelete), List.of(), null))
                .isInstanceOf(KnowledgeConflictException.class);
        verify(pageRepository, never()).save(any());

        PageWrite goodDelete = new PageWrite("notes/a.md", null, 2, true);
        List<PageWriteResult> results = service.batchWrite(PROJECT_ID, List.of(goodDelete), List.of(), null);
        assertThat(results.get(0).version()).isEqualTo(3);
    }

    @Test
    void deleteOfNonexistentPageIsConflict() {
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.empty());

        PageWrite write = new PageWrite("notes/a.md", null, 1, true);
        assertThatThrownBy(() -> service.batchWrite(PROJECT_ID, List.of(write), List.of(), null))
                .isInstanceOf(KnowledgeConflictException.class);
    }

    @Test
    void anyConflictInBatchAbortsTheWholeBatchWritingNothing() {
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.empty());
        KnowledgePage existing = existingPage("notes/b.md", 2);
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/b.md")).thenReturn(Optional.of(existing));

        PageWrite ok = new PageWrite("notes/a.md", DOC, null, false);
        PageWrite conflicting = new PageWrite("notes/b.md", DOC, 1, false);

        assertThatThrownBy(() -> service.batchWrite(PROJECT_ID, List.of(ok, conflicting), List.of(), null))
                .isInstanceOfSatisfying(KnowledgeConflictException.class,
                        ex -> assertThat(ex.conflicts()).extracting(KnowledgeConflictException.Conflict::path)
                                .containsExactly("notes/b.md"));
        verifyNoInteractions(revisionRepository, linkRepository, sourceRepository);
        verify(pageRepository, never()).save(any());
    }

    @Test
    void batchWriteMarksSourcesProcessedInSameCall() {
        when(pageRepository.findByProjectIdAndPath(PROJECT_ID, "notes/a.md")).thenReturn(Optional.empty());

        service.batchWrite(PROJECT_ID, List.of(new PageWrite("notes/a.md", DOC, null, false)),
                List.of("src-1", "src-2"), new Actor("workflow", "wf-1", "run-1"));

        verify(sourceRepository).markProcessed(PROJECT_ID, List.of("src-1", "src-2"));
    }

    @Test
    void emptyBatchReturnsEmptyResultsWithoutTouchingRepositories() {
        List<PageWriteResult> results = service.batchWrite(PROJECT_ID, List.of(), List.of(), null);

        assertThat(results).isEmpty();
        verifyNoInteractions(pageRepository, revisionRepository, linkRepository, sourceRepository);
    }

    private KnowledgePage existingPage(String path, int version) {
        KnowledgePage page = new KnowledgePage();
        page.setId("page-" + path.hashCode());
        page.setProjectId(PROJECT_ID);
        page.setPath(path);
        page.setPageType("note");
        page.setTitle("Existing");
        page.setFrontmatter(java.util.Map.of("type", "note", "title", "Existing"));
        page.setBody("Existing body.");
        page.setContentHash("deadbeef");
        page.setVersion(version);
        page.setDeleted(false);
        page.setCreatedAt(OffsetDateTime.now());
        page.setUpdatedAt(OffsetDateTime.now());
        return page;
    }
}
