package com.conductor.knowledge;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.service.ProjectSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adapter side of the ingestion anti-corruption boundary: translates a {@link NotificationEvent} into
 * a {@link KnowledgeSubmission} and hands it to {@link KnowledgeIngestionService}. Lives at the edge
 * (imports {@code com.conductor.notification} types) specifically so
 * {@code KnowledgeIngestionService} never has to -- the domain-agnostic ingestion inbox must not know
 * what a "Work Item status change" is.
 *
 * <p>Wired as a fourth consumer in {@code NotificationDispatcher.dispatch}, mirroring the existing
 * {@code workflowTriggerService.onConductorEvent}/{@code lifecycleTriggerDispatcher.onConductorEvent}
 * consumers: called unconditionally, with its own try/catch so an ingestion failure never blocks
 * notification delivery or workflow/lifecycle triggering.
 */
@Component
public class KnowledgeEventTap {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEventTap.class);
    private static final String SOURCE_TYPE = "conductor.work_item.status_changed";

    private final KnowledgeIngestionService ingestionService;
    private final ProjectSettingsService projectSettingsService;
    private final ObjectMapper objectMapper;

    public KnowledgeEventTap(KnowledgeIngestionService ingestionService,
                             ProjectSettingsService projectSettingsService,
                             ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.projectSettingsService = projectSettingsService;
        this.objectMapper = objectMapper;
    }

    public void onConductorEvent(NotificationEvent event) {
        if (event.getEventType() != EventType.WORK_ITEM_STATUS_CHANGED) {
            return;
        }
        String projectId = event.getProjectId();
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }

        Map<String, String> metadata = event.getMetadata();
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
}
