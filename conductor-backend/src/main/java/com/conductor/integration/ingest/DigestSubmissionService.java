package com.conductor.integration.ingest;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestSpec;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.knowledge.SourceReceipt;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a SUCCEEDED {@code metrics-narrator} run's {@code narrate} step output and files the
 * resulting narrative into the Knowledge Center inbox -- <b>the platform submits, never the agent</b>.
 * The narrator agent is seeded with zero tools (see
 * {@code KnowledgeWorkflowProvisioner#seedMetricsAnalystAgent}) precisely so this is the only path a
 * digest can ever reach the wiki: it cannot call {@code write_knowledge_pages} or
 * {@code submit_knowledge_source} itself.
 *
 * <p>Called from {@code ConnectorFeedScheduler}'s sweep once a NARRATING digest's run is observed
 * SUCCESS. A schema-conformant but empty {@code narrative} is treated as a FAILED attempt, not a
 * success -- {@link #trySubmit} returns {@code false} and the caller resurrects/dead-letters the digest
 * the same way it would a FAILED run, rather than silently marking the period done with nothing filed.
 */
@Service
public class DigestSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(DigestSubmissionService.class);

    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final ConnectorFeedRepository feedRepository;
    private final ConnectorFeedDigestRepository digestRepository;
    private final ConnectorRegistry connectorRegistry;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ObjectMapper objectMapper;

    /** Self-reference so the {@code REQUIRES_NEW} status write runs through the Spring proxy -- see
     *  {@code LibrarianDispatchService#self}. */
    @Autowired
    @Lazy
    DigestSubmissionService self;

    public DigestSubmissionService(WorkflowJobRunRepository jobRunRepository,
                                   WorkflowStepRunRepository stepRunRepository,
                                   ConnectorFeedRepository feedRepository,
                                   ConnectorFeedDigestRepository digestRepository,
                                   ConnectorRegistry connectorRegistry,
                                   KnowledgeIngestionService knowledgeIngestionService,
                                   ObjectMapper objectMapper) {
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.feedRepository = feedRepository;
        this.digestRepository = digestRepository;
        this.connectorRegistry = connectorRegistry;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts to submit {@code digest}'s narration from the given SUCCEEDED {@code runId}. Returns
     * {@code true} (and moves {@code digest} to SUBMITTED) once a knowledge source exists for it --
     * whether this call's {@link KnowledgeIngestionService#submit} won the insert race (ACCEPTED) or
     * lost it to an earlier attempt (DUPLICATE); treating DUPLICATE as failure would retry forever.
     * Returns {@code false} (digest left untouched) if the step output is missing, unparsable, or the
     * narrative is blank -- the caller is responsible for resurrecting or dead-lettering in that case.
     */
    public boolean trySubmit(ConnectorFeedDigest digest, String runId) {
        Optional<WorkflowStepRun> stepRun = findNarrateStep(runId);
        if (stepRun.isEmpty() || stepRun.get().getOutputJson() == null || stepRun.get().getOutputJson().isBlank()) {
            log.warn("Metrics-narrator run {} for digest {} has no 'narrate' step output -- treating as a "
                    + "failed attempt", runId, digest.getId());
            return false;
        }

        Map<String, Object> output;
        try {
            output = objectMapper.readValue(stepRun.get().getOutputJson(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("Metrics-narrator run {} for digest {} has unparsable 'narrate' step output -- treating "
                    + "as a failed attempt: {}", runId, digest.getId(), e.getMessage());
            return false;
        }

        String title = asString(output.get("title"));
        String narrative = asString(output.get("narrative"));
        String significance = asString(output.get("significance"));
        if (narrative == null || narrative.isBlank()) {
            log.warn("Metrics-narrator run {} for digest {} returned a schema-conformant but blank narrative "
                    + "-- treating as a failed attempt", runId, digest.getId());
            return false;
        }

        ConnectorFeed feed = feedRepository.findById(digest.getFeedId())
                .orElseThrow(() -> new EntityNotFoundException("connector_feed not found: " + digest.getFeedId()));
        IngestSpec spec = resolveSpec(feed);

        String sourceType = resolveSourceType(spec, feed.getConnectorId(), digest.getPeriodKey());
        String sourceRef = "connector://" + feed.getConnectorId() + "/" + feed.getConnectionId() + "/"
                + feed.getIngestId() + "@" + digest.getPeriodKey();

        KnowledgeSubmission submission = new KnowledgeSubmission(
                feed.getProjectId(),
                sourceType,
                sourceRef,
                title,
                "text/markdown",
                narrative,
                digest.getWindowEnd(),
                digest.getDedupKey(),
                new KnowledgeSubmission.Origin("connector_feed_digest", digest.getId()),
                submissionMetadata(digest, feed, significance),
                spec != null ? spec.suggestedDomain() : null);

        SourceReceipt receipt = knowledgeIngestionService.submit(submission);
        self.markSubmittedInNewTx(digest.getId(), receipt.sourceId());
        log.info("Submitted metrics-narrator digest {} as knowledge source {} ({})",
                digest.getId(), receipt.sourceId(), receipt.status());
        return true;
    }

    /** The digest's job run only ever has one job ({@code narrate}); {@code findByRunId} still returns
     *  a list for generality with multi-job workflows, so this takes the first (only) one. */
    private Optional<WorkflowStepRun> findNarrateStep(String runId) {
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        if (jobRuns.isEmpty()) {
            return Optional.empty();
        }
        return stepRunRepository.findByJobRunIdAndStepId(jobRuns.get(0).getId(), "narrate");
    }

    /** Never the raw digest payload -- only the narrative prose the agent returned, plus enough
     *  structured context (period, significance, which metrics were material, movers, target page) for
     *  a reader or future tooling to trace the source without re-deriving it from the change report. */
    private Map<String, Object> submissionMetadata(ConnectorFeedDigest digest, ConnectorFeed feed, String significance) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("periodKey", digest.getPeriodKey());
        metadata.put("significance", significance);
        metadata.put("feedId", feed.getId());
        Map<String, Object> changeReport = digest.getChangeReport();
        if (changeReport != null) {
            metadata.put("materialMetricKeys", materialMetricKeys(changeReport));
            metadata.put("movers", changeReport.get("dimensions"));
            metadata.put("pagePath", changeReport.get("pagePath"));
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private List<String> materialMetricKeys(Map<String, Object> changeReport) {
        Object raw = changeReport.get("metrics");
        List<String> keys = new ArrayList<>();
        if (!(raw instanceof List<?> metrics)) {
            return keys;
        }
        for (Object o : metrics) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> metric = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(metric.get("material"))) {
                Object key = metric.get("key");
                if (key != null) keys.add(String.valueOf(key));
            }
        }
        return keys;
    }

    /** Same lookup as {@code FeedPullService#resolveSpec} -- duplicated rather than shared, since it's
     *  a small, self-contained resolution and the two callers otherwise have nothing in common. */
    private IngestSpec resolveSpec(ConnectorFeed feed) {
        return connectorRegistry.getById(feed.getConnectorId())
                .map(Connector::getToolSpec)
                .map(spec -> spec.ingest())
                .flatMap(specs -> specs.stream().filter(s -> s.id().equals(feed.getIngestId())).findFirst())
                .orElse(null);
    }

    /** Rebuilds the same {@code sourceType} a pull would have resolved for this period, without
     *  re-pulling -- same placeholder substitution as {@code SnapshotIngestAdapter#resolveTemplate}. */
    private String resolveSourceType(IngestSpec spec, String connectorId, String periodKey) {
        if (spec == null || spec.sourceType() == null) {
            return "metrics.digest." + connectorId;
        }
        return spec.sourceType()
                .replace("{connector}", connectorId)
                .replace("{ingest}", spec.id())
                .replace("{period}", periodKey);
    }

    private String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSubmittedInNewTx(String digestId, String knowledgeSourceId) {
        digestRepository.findById(digestId).ifPresent(digest -> {
            digest.setStatus(DigestStatus.SUBMITTED);
            digest.setKnowledgeSourceId(knowledgeSourceId);
            digestRepository.save(digest);
        });
    }
}
