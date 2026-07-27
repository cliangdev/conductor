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
import java.util.Map;
import java.util.TreeMap;

/**
 * Adapter side of the ingestion anti-corruption boundary: translates a {@link
 * SignalTypes#CONDUCTOR_WORK_ITEM_STATUS_CHANGED} {@link Signal} into a {@link KnowledgeSubmission} and
 * hands it to {@link KnowledgeIngestionService}. A {@link SignalSubscriber} at {@link
 * SignalDispatchOrder#KNOWLEDGE} (last in dispatch order) -- formerly a translator over a separate {@code
 * KnowledgeEventTap} collaborator, folded directly in here since this class was already written as the
 * anti-corruption adapter (it, not {@code KnowledgeIngestionService}, is what's allowed to know what a
 * "Work Item status change" is).
 */
@Component
public class KnowledgeSignalSink implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSignalSink.class);
    private static final String SOURCE_TYPE = "conductor.work_item.status_changed";

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
        return SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        // Defense-in-depth: interestedIn already filters to this type before the bus ever calls
        // onSignal, but several unit tests call onSignal directly and rely on the same no-op contract.
        if (!SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signal.type())) {
            return;
        }
        try {
            ingest(signal);
        } catch (Exception e) {
            log.warn("Knowledge ingestion tap failed for event {}: {}", signal.type(), e.getMessage());
        }
    }

    private void ingest(Signal signal) {
        String projectId = signal.projectId();
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }

        Map<String, String> metadata = signal.flatAttributes();
        String workItemRef = metadata.getOrDefault("workItemId", "unknown");
        String fromStatus = metadata.get("fromStatus");
        String toStatus = metadata.get("toStatus");

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
                Map.of("fromStatus", String.valueOf(fromStatus), "toStatus", String.valueOf(toStatus)),
                null); // domain: no hardcoded area->domain map (see plan) -- registry patterns route, or the null lane

        ingestionService.submit(submission);
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

    @Override
    public int order() {
        return SignalDispatchOrder.KNOWLEDGE;
    }
}
