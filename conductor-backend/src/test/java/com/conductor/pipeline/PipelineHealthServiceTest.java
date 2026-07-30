package com.conductor.pipeline;

import com.conductor.entity.WebhookEventStatus;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.integration.ingest.DigestStatus;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSourceCountsView;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;
import com.conductor.knowledge.page.KnowledgePageRevisionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (collaborators mocked) for {@link PipelineHealthService}: asserts each stage's
 * counts assemble correctly from its backing repository, that unrepresented statuses zero-fill
 * rather than being absent (same convention as {@code KnowledgeIngestionService#getSourceCounts}),
 * and in particular that the DIGESTS stage's {@code skipped} bucket is always its own visible count
 * -- the detail issue #342 hinges on for making a quiet-by-design period legible.
 */
@ExtendWith(MockitoExtension.class)
class PipelineHealthServiceTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private WebhookEventRepository webhookEventRepository;
    @Mock private ConnectorFeedRepository connectorFeedRepository;
    @Mock private ConnectorFeedDigestRepository connectorFeedDigestRepository;
    @Mock private KnowledgeIngestionService knowledgeIngestionService;
    @Mock private WorkflowDefinitionRepository workflowDefinitionRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private KnowledgePageRevisionRepository knowledgePageRevisionRepository;

    private PipelineHealthService service;

    @BeforeEach
    void setUp() {
        service = new PipelineHealthService(webhookEventRepository, connectorFeedRepository,
                connectorFeedDigestRepository, knowledgeIngestionService, workflowDefinitionRepository,
                workflowRunRepository, knowledgePageRevisionRepository);

        when(webhookEventRepository.countByProjectIdGroupByStatus(PROJECT_ID)).thenReturn(List.of());
        when(connectorFeedRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(connectorFeedDigestRepository.countByProjectIdGroupByStatus(PROJECT_ID)).thenReturn(List.of());
        when(knowledgeIngestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(0, 0, 0, 0, 0));
        when(workflowDefinitionRepository.findByProjectIdAndName(PROJECT_ID,
                KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)).thenReturn(Optional.empty());
        when(knowledgePageRevisionRepository.countByPage_ProjectIdAndCreatedAtAfter(anyString(), any()))
                .thenReturn(0L);
    }

    @Test
    void everyStageIsPresentAndZeroFilledWithNoData() {
        List<PipelineStageHealthView> stages = service.getHealth(PROJECT_ID);

        assertThat(stages).extracting(PipelineStageHealthView::stage)
                .containsExactly("WEBHOOKS", "FEEDS", "DIGESTS", "INBOX", "LIBRARIAN_RUNS", "PAGES_WRITTEN");
        assertThat(stages.get(0).counts()).containsEntry("pending", 0L).containsEntry("dead", 0L);
        assertThat(stages.get(2).counts()).containsEntry("skipped", 0L);
    }

    @Test
    void digestStageSurfacesSkippedAsItsOwnBucketNotFoldedIntoAnother() {
        when(connectorFeedDigestRepository.countByProjectIdGroupByStatus(PROJECT_ID)).thenReturn(List.of(
                new Object[]{DigestStatus.SKIPPED, 12L},
                new Object[]{DigestStatus.SUBMITTED, 3L}));

        PipelineStageHealthView digests = service.getHealth(PROJECT_ID).get(2);

        assertThat(digests.counts()).containsEntry("skipped", 12L);
        assertThat(digests.counts()).containsEntry("submitted", 3L);
        assertThat(digests.counts()).containsEntry("pending", 0L);
        assertThat(digests.counts()).containsEntry("dead", 0L);
    }

    /** Mirrors the DIGESTS stage's own skipped-bucket guarantee (see the class javadoc) -- INBOX must
     *  always report a {@code skipped} count, present and zero when the project has none. */
    @Test
    void inboxStageAlwaysReportsSkippedBucket() {
        PipelineStageHealthView inbox = service.getHealth(PROJECT_ID).get(3);

        assertThat(inbox.counts()).containsEntry("skipped", 0L);

        when(knowledgeIngestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(1, 2, 3, 4, 5));
        PipelineStageHealthView withSkipped = service.getHealth(PROJECT_ID).get(3);

        assertThat(withSkipped.counts()).containsEntry("skipped", 4L);
        assertThat(withSkipped.counts()).containsEntry("processed", 3L);
        assertThat(withSkipped.counts()).containsEntry("dead", 5L);
    }

    @Test
    void webhookStageTalliesCountsByStatus() {
        when(webhookEventRepository.countByProjectIdGroupByStatus(PROJECT_ID)).thenReturn(List.of(
                new Object[]{WebhookEventStatus.PROCESSED, 40L},
                new Object[]{WebhookEventStatus.DEAD, 2L}));

        PipelineStageHealthView webhooks = service.getHealth(PROJECT_ID).get(0);

        assertThat(webhooks.counts()).containsEntry("processed", 40L);
        assertThat(webhooks.counts()).containsEntry("dead", 2L);
        assertThat(webhooks.counts()).containsEntry("pending", 0L);
    }

    @Test
    void feedStageFlagsAStaleActiveFeedThatHasSucceededBeforeButGoneQuiet() {
        ConnectorFeed stale = feed(ConnectorFeedStatus.ACTIVE, 60, OffsetDateTime.now().minusHours(5));
        ConnectorFeed freshlyEnabled = feed(ConnectorFeedStatus.ACTIVE, 60, null);
        when(connectorFeedRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(stale, freshlyEnabled));

        PipelineStageHealthView feeds = service.getHealth(PROJECT_ID).get(1);

        assertThat(feeds.counts()).containsEntry("active", 2L);
        assertThat(feeds.counts()).containsEntry("stale", 1L); // only the one with a stale lastSuccessAt
    }

    @Test
    void librarianRunStageIsZeroWhenTheProjectHasNoLibrarianWorkflowYet() {
        // setUp already stubs findByProjectIdAndName -> empty; asserts graceful degradation, not an error.
        PipelineStageHealthView runs = service.getHealth(PROJECT_ID).get(4);

        assertThat(runs.counts().values()).allMatch(v -> v == 0L);
    }

    @Test
    void librarianRunStageTalliesRecentRunsByCollapsedBucket() {
        WorkflowDefinition librarian = new WorkflowDefinition();
        librarian.setId("wf-librarian");
        when(workflowDefinitionRepository.findByProjectIdAndName(PROJECT_ID,
                KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)).thenReturn(Optional.of(librarian));

        WorkflowRun success = run(WorkflowRunStatus.SUCCESS);
        WorkflowRun running = run(WorkflowRunStatus.RUNNING);
        Page<WorkflowRun> page = new PageImpl<>(List.of(success, running));
        when(workflowRunRepository.findByWorkflowId(anyString(), any())).thenReturn(page);

        PipelineStageHealthView runs = service.getHealth(PROJECT_ID).get(4);

        assertThat(runs.counts()).containsEntry("success", 1L);
        assertThat(runs.counts()).containsEntry("running", 1L);
        assertThat(runs.counts()).containsEntry("failed", 0L);
    }

    private static ConnectorFeed feed(ConnectorFeedStatus status, int intervalMinutes, OffsetDateTime lastSuccessAt) {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setStatus(status);
        feed.setIntervalMinutes(intervalMinutes);
        feed.setLastSuccessAt(lastSuccessAt);
        return feed;
    }

    private static WorkflowRun run(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setStatus(status);
        run.setStartedAt(OffsetDateTime.now());
        return run;
    }
}
