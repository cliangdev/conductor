package com.conductor.pipeline;

import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WorkflowRun;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedDigest;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.knowledge.KnowledgeSource;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.conductor.knowledge.page.KnowledgePage;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageRevisionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Walks the causal chain from one typed anchor (a page, a knowledge source, a connector feed, or a
 * webhook event) across the pipeline stages that produced or consumed it (issue #342). Everything
 * here is a read-only join over existing real FKs (`processing_run_id`, `knowledge_source_id`,
 * `knowledge_revision_sources`) plus the trace id threaded through `Signal` in D1-D2
 * (`webhook_event.trace_id` &lt;-&gt; `knowledge_sources.metadata-&gt;&gt;'traceId'`) -- no new
 * persistence, no bus durability change.
 *
 * <p><b>Scope note:</b> a {@code Signal} fired directly into workflow automation/lifecycle with no
 * knowledge source involved (see {@code WorkflowTriggerService}) is out of scope here -- the
 * published {@code PipelineStage} enum only names knowledge-pipeline stages (issue #342's own
 * proposed surface), so a generic "automation run" node has nowhere to render. Extending the stage
 * set to cover that is a natural follow-on, not required for this phase.
 *
 * <p><b>Retention degradation.</b> A referenced id that no longer resolves (a {@code DEAD} source
 * retention hard-deleted after 90d) yields a terminal {@link PipelineTraceNodeView#degradedPlaceholder}
 * node instead of throwing -- {@code docs/knowledge.md}'s retention section is explicit that
 * historical traces go dangling by design, so the view must degrade gracefully rather than error.
 * A {@code PROCESSED} source whose payload was merely compacted ({@code purgedAt} set) is NOT
 * degraded: the row, its status, and its {@code metadata} (including any traceId) all survive
 * compaction -- only the payload content is gone, which the trace never reads.
 */
@Service
public class PipelineTraceService {

    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgePageRepository knowledgePageRepository;
    private final KnowledgePageRevisionRepository knowledgePageRevisionRepository;
    private final ConnectorFeedRepository connectorFeedRepository;
    private final ConnectorFeedDigestRepository connectorFeedDigestRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final WorkflowRunRepository workflowRunRepository;

    public PipelineTraceService(KnowledgeSourceRepository knowledgeSourceRepository,
                                 KnowledgePageRepository knowledgePageRepository,
                                 KnowledgePageRevisionRepository knowledgePageRevisionRepository,
                                 ConnectorFeedRepository connectorFeedRepository,
                                 ConnectorFeedDigestRepository connectorFeedDigestRepository,
                                 WebhookEventRepository webhookEventRepository,
                                 WorkflowRunRepository workflowRunRepository) {
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.knowledgePageRepository = knowledgePageRepository;
        this.knowledgePageRevisionRepository = knowledgePageRevisionRepository;
        this.connectorFeedRepository = connectorFeedRepository;
        this.connectorFeedDigestRepository = connectorFeedDigestRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    /**
     * Exactly one of the four anchors must be non-null/non-blank -- enforced here (not left to the
     * controller) since "exactly one anchor" is trace-walk business logic, not request plumbing.
     */
    @Transactional(readOnly = true)
    public List<PipelineTraceNodeView> trace(String projectId, String pageId, String sourceId,
                                              String feedId, String webhookEventId) {
        long provided = Stream.of(pageId, sourceId, feedId, webhookEventId)
                .filter(v -> v != null && !v.isBlank()).count();
        if (provided != 1) {
            throw new BusinessException("Exactly one of pageId, sourceId, feedId, webhookEventId is required");
        }
        if (pageId != null && !pageId.isBlank()) {
            return traceFromPage(projectId, pageId);
        }
        if (sourceId != null && !sourceId.isBlank()) {
            return traceFromSource(projectId, sourceId);
        }
        if (feedId != null && !feedId.isBlank()) {
            return traceFromFeed(projectId, feedId);
        }
        return traceFromWebhookEvent(projectId, webhookEventId);
    }

    // ---- anchors ----

    private List<PipelineTraceNodeView> traceFromPage(String projectId, String pageId) {
        List<PipelineTraceNodeView> nodes = new ArrayList<>();
        Optional<KnowledgePage> page = knowledgePageRepository.findById(pageId)
                .filter(p -> p.getProjectId().equals(projectId));
        if (page.isEmpty()) {
            nodes.add(PipelineTraceNodeView.degradedPlaceholder("PAGES_WRITTEN", pageId));
            return nodes;
        }
        for (String linkedSourceId : knowledgePageRevisionRepository.findSourceIdsByPageId(pageId)) {
            appendSourceBackward(nodes, linkedSourceId);
        }
        nodes.add(pageNode(page.get()));
        return nodes;
    }

    private List<PipelineTraceNodeView> traceFromSource(String projectId, String sourceId) {
        List<PipelineTraceNodeView> nodes = new ArrayList<>();
        Optional<KnowledgeSource> source = knowledgeSourceRepository.findById(sourceId)
                .filter(s -> s.getProjectId().equals(projectId));
        if (source.isEmpty()) {
            nodes.add(PipelineTraceNodeView.degradedPlaceholder("INBOX", sourceId));
            return nodes;
        }
        appendWebhookBackward(nodes, source.get());
        nodes.add(sourceNode(source.get()));
        appendRunForward(nodes, source.get().getProcessingRunId());
        appendPagesForward(nodes, sourceId);
        return nodes;
    }

    private List<PipelineTraceNodeView> traceFromFeed(String projectId, String feedId) {
        List<PipelineTraceNodeView> nodes = new ArrayList<>();
        Optional<ConnectorFeed> feed = connectorFeedRepository.findById(feedId)
                .filter(f -> f.getProjectId().equals(projectId));
        if (feed.isEmpty()) {
            nodes.add(PipelineTraceNodeView.degradedPlaceholder("FEEDS", feedId));
            return nodes;
        }
        nodes.add(feedNode(feed.get()));
        for (ConnectorFeedDigest digest : connectorFeedDigestRepository.findTop20ByFeedIdOrderByCreatedAtDesc(feedId)) {
            nodes.add(digestNode(digest));
            if (digest.getKnowledgeSourceId() != null) {
                Optional<KnowledgeSource> source = knowledgeSourceRepository.findById(digest.getKnowledgeSourceId());
                if (source.isPresent()) {
                    nodes.add(sourceNode(source.get()));
                    appendRunForward(nodes, source.get().getProcessingRunId());
                    appendPagesForward(nodes, source.get().getId());
                } else {
                    nodes.add(PipelineTraceNodeView.degradedPlaceholder("INBOX", digest.getKnowledgeSourceId()));
                }
            }
        }
        return nodes;
    }

    private List<PipelineTraceNodeView> traceFromWebhookEvent(String projectId, String webhookEventId) {
        List<PipelineTraceNodeView> nodes = new ArrayList<>();
        Optional<WebhookEvent> webhookEvent = webhookEventRepository.findByIdAndProjectId(webhookEventId, projectId);
        if (webhookEvent.isEmpty()) {
            nodes.add(PipelineTraceNodeView.degradedPlaceholder("WEBHOOKS", webhookEventId));
            return nodes;
        }
        nodes.add(webhookNode(webhookEvent.get()));
        String traceId = webhookEvent.get().getTraceId();
        if (traceId != null) {
            for (KnowledgeSource source : knowledgeSourceRepository.findByMetadataTraceId(traceId)) {
                nodes.add(sourceNode(source));
                appendRunForward(nodes, source.getProcessingRunId());
                appendPagesForward(nodes, source.getId());
            }
        }
        return nodes;
    }

    // ---- shared edges ----

    /** Backward: a source's stamped traceId (see D1-D2) to the webhook event that caused it, if any --
     *  best-effort, takes the earliest match. Project-scoped (not the unscoped {@code findByTraceId})
     *  so a source's traceId can never be used to pull a webhook event from another project. */
    private void appendWebhookBackward(List<PipelineTraceNodeView> nodes, KnowledgeSource source) {
        Object traceId = source.getMetadata() != null ? source.getMetadata().get("traceId") : null;
        if (traceId instanceof String traceIdStr) {
            webhookEventRepository.findByTraceIdAndProjectId(traceIdStr, source.getProjectId()).stream()
                    .min(java.util.Comparator.comparing(WebhookEvent::getReceivedAt))
                    .ifPresent(webhookEvent -> nodes.add(webhookNode(webhookEvent)));
        }
    }

    /** Backward, from a page: a linked source id may have been purged since the revision was written. */
    private void appendSourceBackward(List<PipelineTraceNodeView> nodes, String sourceId) {
        Optional<KnowledgeSource> source = knowledgeSourceRepository.findById(sourceId);
        if (source.isEmpty()) {
            nodes.add(PipelineTraceNodeView.degradedPlaceholder("INBOX", sourceId));
            return;
        }
        appendWebhookBackward(nodes, source.get());
        nodes.add(sourceNode(source.get()));
    }

    /** Forward: a source's processingRunId to the librarian run that (attempted to) file it. Absent
     *  entirely just means the source hasn't been claimed yet -- not degraded, simply not there yet. */
    private void appendRunForward(List<PipelineTraceNodeView> nodes, String processingRunId) {
        if (processingRunId == null) {
            return;
        }
        Optional<WorkflowRun> run = workflowRunRepository.findById(processingRunId);
        if (run.isPresent()) {
            nodes.add(runNode(run.get()));
        } else {
            nodes.add(PipelineTraceNodeView.degradedPlaceholder("LIBRARIAN_RUNS", processingRunId));
        }
    }

    private void appendPagesForward(List<PipelineTraceNodeView> nodes, String sourceId) {
        for (KnowledgePageRevisionRepository.PageRef pageRef : knowledgePageRevisionRepository.findPagesBySourceId(sourceId)) {
            nodes.add(new PipelineTraceNodeView("PAGES_WRITTEN", pageRef.getPageId(), null, null,
                    pageRef.getPath(), "/knowledge/page?path=" + pageRef.getPath(), false));
        }
    }

    // ---- node builders ----

    private PipelineTraceNodeView webhookNode(WebhookEvent event) {
        return new PipelineTraceNodeView("WEBHOOKS", event.getId(), event.getStatus().name(),
                event.getReceivedAt(), event.getEventType(), null, false);
    }

    private PipelineTraceNodeView feedNode(ConnectorFeed feed) {
        return new PipelineTraceNodeView("FEEDS", feed.getId(), feed.getStatus().name(),
                feed.getLastRunAt(), feed.getConnectorId() + "/" + feed.getIngestId(),
                "/integrations/" + feed.getConnectorId(), false);
    }

    private PipelineTraceNodeView digestNode(ConnectorFeedDigest digest) {
        return new PipelineTraceNodeView("DIGESTS", digest.getId(), digest.getStatus().name(),
                digest.getCreatedAt(), digest.getPeriodKey(), null, false);
    }

    private PipelineTraceNodeView sourceNode(KnowledgeSource source) {
        return new PipelineTraceNodeView("INBOX", source.getId(), source.getStatus().name(),
                source.getReceivedAt(), source.getTitle() != null ? source.getTitle() : source.getSourceType(),
                null, false);
    }

    private PipelineTraceNodeView runNode(WorkflowRun run) {
        return new PipelineTraceNodeView("LIBRARIAN_RUNS", run.getId(), run.getStatus().name(),
                run.getStartedAt(), null, "/workflows/runs/" + run.getId(), false);
    }

    private PipelineTraceNodeView pageNode(KnowledgePage page) {
        return new PipelineTraceNodeView("PAGES_WRITTEN", page.getId(), page.isDeleted() ? "DELETED" : "LIVE",
                page.getUpdatedAt(), page.getPath(), "/knowledge/page?path=" + page.getPath(), false);
    }
}
