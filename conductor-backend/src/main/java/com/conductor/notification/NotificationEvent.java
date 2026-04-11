package com.conductor.notification;

import java.time.Instant;
import java.util.Map;

public class NotificationEvent {

    private final EventType eventType;
    private final String projectId;
    private final Map<String, String> metadata;
    private final Instant timestamp;

    private NotificationEvent(EventType eventType, String projectId, Map<String, String> metadata, Instant timestamp) {
        this.eventType = eventType;
        this.projectId = projectId;
        this.metadata = metadata;
        this.timestamp = timestamp;
    }

    public static NotificationEvent of(EventType eventType, String projectId, Map<String, String> metadata) {
        return new NotificationEvent(eventType, projectId, Map.copyOf(metadata), Instant.now());
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getProjectId() {
        return projectId;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
