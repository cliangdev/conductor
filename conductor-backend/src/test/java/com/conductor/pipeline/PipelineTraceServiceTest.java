package com.conductor.pipeline;

import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.knowledge.KnowledgeSource;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.conductor.knowledge.KnowledgeSourceStatus;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageRevisionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (collaborators mocked) for {@link PipelineTraceService}: the sourceId anchor's
 * forward walk (run + pages) and backward walk (webhook, via the D1-D2 traceId stamped into
 * {@code metadata}), the mutually-exclusive-anchor validation, and -- the case issue #342 calls out
 * explicitly -- that a referenced-but-since-purged record degrades to a placeholder node instead of
 * throwing.
 */
@ExtendWith(MockitoExtension.class)
class PipelineTraceServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private KnowledgeSourceRepository knowledgeSourceRepository;
    @Mock private KnowledgePageRepository knowledgePageRepository;
    @Mock private KnowledgePageRevisionRepository knowledgePageRevisionRepository;
    @Mock private ConnectorFeedRepository connectorFeedRepository;
    @Mock private ConnectorFeedDigestRepository connectorFeedDigestRepository;
    @Mock private WebhookEventRepository webhookEventRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;

    private PipelineTraceService service;

    @BeforeEach
    void setUp() {
        service = new PipelineTraceService(knowledgeSourceRepository, knowledgePageRepository,
                knowledgePageRevisionRepository, connectorFeedRepository, connectorFeedDigestRepository,
                webhookEventRepository, workflowRunRepository);
    }

    @Test
    void rejectsZeroAnchors() {
        assertThatThrownBy(() -> service.trace(PROJECT_ID, null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMultipleAnchors() {
        assertThatThrownBy(() -> service.trace(PROJECT_ID, "page-1", "source-1", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sourceAnchorWalksBackwardToWebhookAndForwardToRunAndPages() {
        KnowledgeSource source = source("src-1", Map.of("traceId", "trc-abc"));
        source.setProcessingRunId("run-1");
        when(knowledgeSourceRepository.findById("src-1")).thenReturn(Optional.of(source));

        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setId("wh-1");
        webhookEvent.setStatus(WebhookEventStatus.PROCESSED);
        webhookEvent.setReceivedAt(OffsetDateTime.now().minusMinutes(5));
        when(webhookEventRepository.findByTraceIdAndProjectId("trc-abc", PROJECT_ID)).thenReturn(List.of(webhookEvent));

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setStatus(WorkflowRunStatus.SUCCESS);
        run.setStartedAt(OffsetDateTime.now().minusMinutes(3));
        when(workflowRunRepository.findById("run-1")).thenReturn(Optional.of(run));

        when(knowledgePageRevisionRepository.findPagesBySourceId("src-1")).thenReturn(List.of(
                pageRef("page-1", "engineering/architecture/foo.md")));

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, "src-1", null, null);

        assertThat(nodes).extracting(PipelineTraceNodeView::stage)
                .containsExactly("WEBHOOKS", "INBOX", "LIBRARIAN_RUNS", "PAGES_WRITTEN");
        assertThat(nodes).noneMatch(PipelineTraceNodeView::degraded);
        assertThat(nodes.get(3).label()).isEqualTo("engineering/architecture/foo.md");
    }

    @Test
    void sourceAnchorDegradesWhenTheSourceItselfHasBeenPurgedByRetention() {
        when(knowledgeSourceRepository.findById("gone")).thenReturn(Optional.empty());

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, "gone", null, null);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).stage()).isEqualTo("INBOX");
        assertThat(nodes.get(0).degraded()).isTrue();
    }

    @Test
    void sourceAnchorDegradesTheRunNodeWhenProcessingRunIdNoLongerResolves() {
        KnowledgeSource source = source("src-2", Map.of());
        source.setProcessingRunId("run-missing");
        when(knowledgeSourceRepository.findById("src-2")).thenReturn(Optional.of(source));
        when(workflowRunRepository.findById("run-missing")).thenReturn(Optional.empty());
        when(knowledgePageRevisionRepository.findPagesBySourceId("src-2")).thenReturn(List.of());

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, "src-2", null, null);

        assertThat(nodes).extracting(PipelineTraceNodeView::stage).containsExactly("INBOX", "LIBRARIAN_RUNS");
        assertThat(nodes.get(1).degraded()).isTrue();
    }

    @Test
    void sourceAnchorSkipsTheRunNodeWhenNotYetClaimed() {
        KnowledgeSource source = source("src-3", Map.of());
        when(knowledgeSourceRepository.findById("src-3")).thenReturn(Optional.of(source));
        when(knowledgePageRevisionRepository.findPagesBySourceId("src-3")).thenReturn(List.of());

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, "src-3", null, null);

        assertThat(nodes).extracting(PipelineTraceNodeView::stage).containsExactly("INBOX");
    }

    /** A SKIPPED source is a librarian decision, not a broken pipeline -- the trace node must read as
     *  live status, not a degraded placeholder (that's reserved for retention having hard-deleted it). */
    @Test
    void sourceAnchorSkippedStatusIsADecisionNotDegraded() {
        KnowledgeSource source = source("src-4", Map.of());
        source.setStatus(KnowledgeSourceStatus.SKIPPED);
        source.setSkipReason("not material");
        when(knowledgeSourceRepository.findById("src-4")).thenReturn(Optional.of(source));
        when(knowledgePageRevisionRepository.findPagesBySourceId("src-4")).thenReturn(List.of());

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, "src-4", null, null);

        assertThat(nodes).extracting(PipelineTraceNodeView::stage).containsExactly("INBOX");
        assertThat(nodes.get(0).status()).isEqualTo("SKIPPED");
        assertThat(nodes.get(0).degraded()).isFalse();
    }

    @Test
    void webhookEventAnchorUsesTheProjectScopedLookupNotTheGlobalOne() {
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setId("wh-1");
        webhookEvent.setStatus(WebhookEventStatus.PROCESSED);
        webhookEvent.setReceivedAt(OffsetDateTime.now().minusMinutes(5));
        when(webhookEventRepository.findByIdAndProjectId("wh-1", PROJECT_ID)).thenReturn(Optional.of(webhookEvent));

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, null, null, "wh-1");

        assertThat(nodes).extracting(PipelineTraceNodeView::stage).containsExactly("WEBHOOKS");
        assertThat(nodes.get(0).degraded()).isFalse();
    }

    /**
     * A caller can't read another project's webhook event by id: {@code findByIdAndProjectId} is
     * scoped to the calling project, so a webhook event belonging to a different project resolves as
     * "not found" here (never falls back to an unscoped lookup) and degrades like any other purged
     * reference -- this is the IDOR this anchor had before the project-scoping fix (issue #342 review).
     */
    @Test
    void webhookEventAnchorDegradesRatherThanLeakingAnotherProjectsEvent() {
        when(webhookEventRepository.findByIdAndProjectId("wh-other-project", PROJECT_ID)).thenReturn(Optional.empty());

        List<PipelineTraceNodeView> nodes = service.trace(PROJECT_ID, null, null, null, "wh-other-project");

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).stage()).isEqualTo("WEBHOOKS");
        assertThat(nodes.get(0).degraded()).isTrue();
    }

    private static KnowledgeSource source(String id, Map<String, Object> metadata) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(id);
        source.setProjectId(PROJECT_ID);
        source.setSourceType("github.pr_merged");
        source.setStatus(KnowledgeSourceStatus.PROCESSED);
        source.setReceivedAt(OffsetDateTime.now().minusMinutes(4));
        source.setMetadata(metadata);
        return source;
    }

    private static KnowledgePageRevisionRepository.PageRef pageRef(String pageId, String path) {
        return new KnowledgePageRevisionRepository.PageRef() {
            @Override public String getPageId() { return pageId; }
            @Override public String getPath() { return path; }
        };
    }
}
