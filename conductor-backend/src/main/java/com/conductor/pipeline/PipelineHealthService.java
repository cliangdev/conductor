package com.conductor.pipeline;

import com.conductor.entity.WebhookEvent;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only aggregation across the webhook / connector-feed / knowledge-inbox / workflow-run /
 * wiki-revision repositories into one project-scoped "is the pipeline keeping up?" snapshot (issue
 * #342). Deliberately a query-side composition, not a new aggregate or mutation path -- every count
 * here is sourced from a repository that already exists for its own bounded context (only the
 * project-scoped group-by queries needed adding, see {@code WebhookEventRepository},
 * {@code ConnectorFeedDigestRepository}).
 *
 * <p>Stage order matches the pipeline's actual data flow: WEBHOOKS -&gt; FEEDS -&gt; DIGESTS -&gt;
 * INBOX -&gt; LIBRARIAN_RUNS -&gt; PAGES_WRITTEN.
 */
@Service
public class PipelineHealthService {

    /** Bounds the librarian-runs stage to a recent window rather than every run ever fired. */
    private static final int RECENT_RUN_LIMIT = 50;

    /** Bounds the pages-written stage to a recent window, not the wiki's entire lifetime. */
    private static final int PAGES_WRITTEN_WINDOW_DAYS = 30;

    /** A feed that has run before but gone quiet well beyond its own cadence reads as stale --
     *  distinct from a freshly enabled feed that has simply never run yet (not stale, just new). */
    private static final int STALE_INTERVAL_MULTIPLIER = 3;

    private final WebhookEventRepository webhookEventRepository;
    private final ConnectorFeedRepository connectorFeedRepository;
    private final ConnectorFeedDigestRepository connectorFeedDigestRepository;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final KnowledgePageRevisionRepository knowledgePageRevisionRepository;

    public PipelineHealthService(WebhookEventRepository webhookEventRepository,
                                  ConnectorFeedRepository connectorFeedRepository,
                                  ConnectorFeedDigestRepository connectorFeedDigestRepository,
                                  KnowledgeIngestionService knowledgeIngestionService,
                                  WorkflowDefinitionRepository workflowDefinitionRepository,
                                  WorkflowRunRepository workflowRunRepository,
                                  KnowledgePageRevisionRepository knowledgePageRevisionRepository) {
        this.webhookEventRepository = webhookEventRepository;
        this.connectorFeedRepository = connectorFeedRepository;
        this.connectorFeedDigestRepository = connectorFeedDigestRepository;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.knowledgePageRevisionRepository = knowledgePageRevisionRepository;
    }

    @Transactional(readOnly = true)
    public List<PipelineStageHealthView> getHealth(String projectId) {
        return List.of(
                webhookStage(projectId),
                feedStage(projectId),
                digestStage(projectId),
                inboxStage(projectId),
                librarianRunStage(projectId),
                pagesWrittenStage(projectId));
    }

    private PipelineStageHealthView webhookStage(String projectId) {
        Map<String, Long> counts = zeroFilled("pending", "processed", "failed", "dead");
        for (Object[] row : webhookEventRepository.countByProjectIdGroupByStatus(projectId)) {
            WebhookEventStatus status = (WebhookEventStatus) row[0];
            counts.put(status.name().toLowerCase(), (Long) row[1]);
        }
        return new PipelineStageHealthView("WEBHOOKS", "Webhooks", counts);
    }

    private PipelineStageHealthView feedStage(String projectId) {
        Map<String, Long> counts = zeroFilled("active", "paused", "setupRequired", "dead", "stale");
        OffsetDateTime now = OffsetDateTime.now();
        for (ConnectorFeed feed : connectorFeedRepository.findByProjectId(projectId)) {
            switch (feed.getStatus()) {
                case ACTIVE -> counts.merge("active", 1L, Long::sum);
                case PAUSED -> counts.merge("paused", 1L, Long::sum);
                case SETUP_REQUIRED -> counts.merge("setupRequired", 1L, Long::sum);
                case DEAD -> counts.merge("dead", 1L, Long::sum);
            }
            if (isStale(feed, now)) {
                counts.merge("stale", 1L, Long::sum);
            }
        }
        return new PipelineStageHealthView("FEEDS", "Connector feeds", counts);
    }

    /** A feed that has succeeded before but hasn't since well beyond its own cadence -- a freshly
     *  enabled feed with no success yet is normal, not stale (see {@code docs/knowledge.md}'s "a
     *  newly enabled feed files nothing on its own"). */
    private boolean isStale(ConnectorFeed feed, OffsetDateTime now) {
        if (feed.getStatus() != ConnectorFeedStatus.ACTIVE || feed.getLastSuccessAt() == null) {
            return false;
        }
        Duration staleAfter = Duration.ofMinutes((long) feed.getIntervalMinutes() * STALE_INTERVAL_MULTIPLIER);
        return feed.getLastSuccessAt().isBefore(now.minus(staleAfter));
    }

    private PipelineStageHealthView digestStage(String projectId) {
        Map<String, Long> counts = zeroFilled("pending", "narrating", "submitted", "skipped", "dead");
        for (Object[] row : connectorFeedDigestRepository.countByProjectIdGroupByStatus(projectId)) {
            DigestStatus status = (DigestStatus) row[0];
            counts.put(status.name().toLowerCase(), (Long) row[1]);
        }
        return new PipelineStageHealthView("DIGESTS", "Metrics digests", counts);
    }

    private PipelineStageHealthView inboxStage(String projectId) {
        KnowledgeSourceCountsView view = knowledgeIngestionService.getSourceCounts(projectId);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", view.pending());
        counts.put("processing", view.processing());
        counts.put("processed", view.processed());
        counts.put("dead", view.dead());
        return new PipelineStageHealthView("INBOX", "Knowledge inbox", counts);
    }

    private PipelineStageHealthView librarianRunStage(String projectId) {
        Map<String, Long> counts = zeroFilled("pending", "running", "success", "failed", "cancelled");
        Optional<WorkflowDefinition> librarian = workflowDefinitionRepository.findByProjectIdAndName(
                projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME);
        if (librarian.isPresent()) {
            List<WorkflowRun> recentRuns = workflowRunRepository.findByWorkflowId(librarian.get().getId(),
                    PageRequest.of(0, RECENT_RUN_LIMIT, Sort.by(Sort.Direction.DESC, "startedAt"))).getContent();
            for (WorkflowRun run : recentRuns) {
                counts.merge(bucketFor(run.getStatus()), 1L, Long::sum);
            }
        }
        return new PipelineStageHealthView("LIBRARIAN_RUNS", "Librarian runs", counts);
    }

    /** Collapses the run-status enum's pickup/cancelling substates into the five buckets the UI
     *  actually distinguishes -- the same "in flight" simplification {@code WorkflowRunStatus} itself
     *  documents via {@code ACTIVE_RUN_STATUSES}. */
    private String bucketFor(WorkflowRunStatus status) {
        return switch (status) {
            case PENDING, PENDING_LOCAL_PICKUP -> "pending";
            case RUNNING, CANCELLING -> "running";
            case SUCCESS -> "success";
            case FAILED, LOCAL_PICKUP_TIMEOUT -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    private PipelineStageHealthView pagesWrittenStage(String projectId) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(PAGES_WRITTEN_WINDOW_DAYS);
        long written = knowledgePageRevisionRepository.countByPage_ProjectIdAndCreatedAtAfter(projectId, since);
        return new PipelineStageHealthView("PAGES_WRITTEN", "Pages written (30d)", Map.of("written", written));
    }

    private static Map<String, Long> zeroFilled(String... buckets) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String bucket : buckets) {
            counts.put(bucket, 0L);
        }
        return counts;
    }
}
