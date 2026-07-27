package com.conductor.knowledge.signal;

import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.service.ProjectSettingsService;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adapter side of the ingestion anti-corruption boundary: translates a {@link
 * SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED} or {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED}
 * {@link Signal} into a {@link KnowledgeSubmission} and hands it to {@link KnowledgeIngestionService}. A
 * {@link SignalSubscriber} at {@link SignalDispatchOrder#KNOWLEDGE} -- formerly a translator over a
 * separate {@code KnowledgeEventTap} collaborator, folded directly in here since this class was already
 * written as the anti-corruption adapter (it, not {@code KnowledgeIngestionService}, is what's allowed to
 * know what a "Work Item status change" or a "merged PR" is).
 *
 * <p>As of A8, the merged-PR submission (formerly {@code GitHubConnector.submitMergedPrKnowledge}) lives
 * here too -- it submits regardless of whether the PR body references a Conductor issue, unlike {@code
 * PullRequestMergeSubscriber} (order {@link SignalDispatchOrder#PULL_REQUEST_MERGE}, which runs after
 * this sink so the ordering matches today's submit-then-complete sequence).
 */
@Component
public class KnowledgeSignalSink implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSignalSink.class);
    private static final String SOURCE_TYPE = "conductor.work_item.status_changed";
    private static final String MERGED_PR_SOURCE_TYPE = "github.pr_merged";

    private final KnowledgeIngestionService ingestionService;
    private final ProjectSettingsService projectSettingsService;
    private final ObjectMapper objectMapper;

    public KnowledgeSignalSink(KnowledgeIngestionService ingestionService,
                               ProjectSettingsService projectSettingsService,
                               ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.projectSettingsService = projectSettingsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "knowledge-ingestion";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signalType)
                || SignalTypes.GITHUB_PULL_REQUEST_MERGED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        // Defense-in-depth: interestedIn already filters to these types before the bus ever calls
        // onSignal, but several unit tests call onSignal directly and rely on the same no-op contract.
        try {
            if (SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signal.type())) {
                ingestWorkItemStatusChanged(signal);
            } else if (SignalTypes.GITHUB_PULL_REQUEST_MERGED.equals(signal.type())) {
                ingestMergedPullRequest(signal);
            }
        } catch (Exception e) {
            log.warn("Knowledge ingestion tap failed for event {}: {}", signal.type(), e.getMessage());
        }
    }

    private void ingestWorkItemStatusChanged(Signal signal) {
        String projectId = signal.projectId();
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }

        Map<String, String> metadata = signal.flatAttributes();
        String workItemRef = metadata.getOrDefault("workItemId", "unknown");
        String fromStatus = metadata.get("fromStatus");
        String toStatus = metadata.get("toStatus");

        Map<String, Object> submissionMetadata = new LinkedHashMap<>();
        submissionMetadata.put("fromStatus", String.valueOf(fromStatus));
        submissionMetadata.put("toStatus", String.valueOf(toStatus));
        stampTraceId(submissionMetadata, signal);

        KnowledgeSubmission submission = new KnowledgeSubmission(
                projectId,
                SOURCE_TYPE,
                "conductor:" + workItemRef,
                metadata.get("workItemTitle"),
                "application/json",
                toJson(metadata),
                OffsetDateTime.now(),
                dedupKey(projectId, workItemRef, fromStatus, toStatus),
                new KnowledgeSubmission.Origin("EVENT_TAP", workItemRef),
                submissionMetadata,
                null); // domain: no hardcoded area->domain map (see plan) -- registry patterns route, or the null lane

        ingestionService.submit(submission);
    }

    /**
     * Stamps the signal's trace id into the submission's metadata jsonb, if present, so a
     * {@code knowledge_sources} row can be joined back to the {@code webhook_event} (or other signal
     * origin) that produced it -- see issue #342. A no-op when {@code signal.traceId()} is null
     * (e.g. a test calling {@code onSignal} directly against a hand-built {@link Signal}).
     */
    private void stampTraceId(Map<String, Object> metadata, Signal signal) {
        if (signal.traceId() != null) {
            metadata.put("traceId", signal.traceId());
        }
    }

    /** Stable across re-dispatches of the identical status transition (e.g. a notification retry). */
    private String dedupKey(String projectId, String workItemRef, String fromStatus, String toStatus) {
        return "work-item-status-changed:" + projectId + ":" + workItemRef + ":" + fromStatus + "->" + toStatus;
    }

    private String toJson(Map<String, String> metadata) {
        try {
            // Sorted so the same underlying event always serializes identically (stable payload for
            // dedup-by-content callers, and deterministic in tests).
            return objectMapper.writeValueAsString(new TreeMap<>(metadata));
        } catch (Exception e) {
            log.warn("Failed to serialize work-item-status-changed metadata for knowledge ingestion: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Formerly {@code GitHubConnector.submitMergedPrKnowledge}: submits a merged PR as a {@code
     * github.pr_merged} source regardless of whether it references a Conductor issue -- unlike {@code
     * PullRequestMergeSubscriber}'s issue-completion path, this is about the codebase, not a specific
     * Work Item. The enablement + shape checks below reproduce that method's guard exactly (silent
     * no-submit, not an error) -- {@link #onSignal}'s try/catch around this call is the same
     * fail-soft contract the connector used to provide with its own try/catch.
     */
    private void ingestMergedPullRequest(Signal signal) {
        String projectId = signal.projectId();
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }

        Map<String, Object> attrs = signal.payload();
        String fullName = (String) attrs.get("repoFullName");
        Integer number = (Integer) attrs.get("number");
        if (fullName == null || fullName.isBlank() || number == null || number < 0) {
            return;
        }

        String sourceRef = "github:" + fullName + "#" + number;
        String title = (String) attrs.get("title");

        Map<String, Object> jsonPayload = new LinkedHashMap<>();
        jsonPayload.put("title", title);
        jsonPayload.put("body", attrs.getOrDefault("body", ""));
        jsonPayload.put("labels", attrs.getOrDefault("labels", List.of()));
        jsonPayload.put("merged_by", attrs.get("mergedBy"));
        jsonPayload.put("baseSha", attrs.get("baseSha"));
        jsonPayload.put("headSha", attrs.get("headSha"));
        if (attrs.containsKey("changedFilesCount")) {
            jsonPayload.put("changedFilesCount", attrs.get("changedFilesCount"));
        }

        Map<String, Object> submissionMetadata = new LinkedHashMap<>();
        stampTraceId(submissionMetadata, signal);

        KnowledgeSubmission submission = new KnowledgeSubmission(
                projectId, MERGED_PR_SOURCE_TYPE, sourceRef, title, "application/json",
                writeJsonPayload(jsonPayload), OffsetDateTime.now(),
                "github-pr-merged:" + sourceRef, new KnowledgeSubmission.Origin("GITHUB_CONNECTOR", sourceRef),
                submissionMetadata.isEmpty() ? null : submissionMetadata,
                null); // domain: null -- the engineering domain's "github.*" pattern routes this

        ingestionService.submit(submission);
    }

    private String writeJsonPayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize merged-PR payload for knowledge ingestion: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.KNOWLEDGE;
    }
}
