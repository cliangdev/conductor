package com.conductor.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Standalone (no Mockito) coverage for the static {@link ProjectDocService#extractSnippet} -- kept out
 * of {@link ProjectDocServiceTest} because that class's {@code @BeforeEach} stubs collaborators these
 * pure-function tests never touch, which trips Mockito's strict-stubbing check.
 *
 * <p>Shared by {@code ProjectDocsController#searchProjectDocs} (REST) and the coordinator's {@code
 * search_project_docs} tool (machine) -- see the method's own javadoc.
 */
class ProjectDocServiceSnippetTest {

    @Test
    void cutsAroundTheFirstCaseInsensitiveHit() {
        String content = "x".repeat(60) + "GIZMO" + "y".repeat(60);

        String snippet = ProjectDocService.extractSnippet(content, "gizmo");

        assertThat(snippet).contains("GIZMO");
        assertThat(snippet.length()).isLessThan(content.length());
    }

    @Test
    void fallsBackToALeadingClipWhenThereIsNoHit() {
        String content = "a".repeat(300);

        String snippet = ProjectDocService.extractSnippet(content, "nomatch");

        assertThat(snippet).hasSize(200);
    }

    @Test
    void returnsEmptyStringForBlankContent() {
        assertThat(ProjectDocService.extractSnippet(null, "q")).isEmpty();
        assertThat(ProjectDocService.extractSnippet("   ", "q")).isEmpty();
    }

    @Test
    void summarizesDocImageMarkersItselfSoNoCallerCanLeakOne() {
        // The leak this method exists to prevent: a snippet is prose shown as-is, so an internal
        // storage-path marker must never survive into one. Summarizing here rather than at each call
        // site is what makes that true for the next caller too, not just today's two.
        String marker = "conductor-image:projects/p1/docs/d1/images/f.png";
        String content = "before " + marker + " after";

        String snippet = ProjectDocService.extractSnippet(content, "after");

        assertThat(snippet).doesNotContain(marker).doesNotContain("conductor-image:");
    }
}
