package com.conductor.notification;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Reconstructs an event with a KNOWN {@code timestamp} rather than stamping "now". Used only by
     * {@code NotificationSignalMapper} when translating a {@code Signal} back into the shape the
     * existing consumers expect: the signal already carries the original occurrence time, so rebuilding
     * with {@code Instant.now()} would silently substitute the translation time for the event time.
     * Nothing reads {@code getTimestamp()} today, which is precisely why such a substitution would go
     * unnoticed until something did.
     */
    public static NotificationEvent of(EventType eventType, String projectId, Map<String, String> metadata,
                                       Instant timestamp) {
        return new NotificationEvent(eventType, projectId, Map.copyOf(metadata), timestamp);
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

    /**
     * Deliberately scoped to {@code (eventType, projectId, metadata)} -- NOT {@code timestamp}. Two
     * events describing the same occurrence are the same event; the timestamp is incidental metadata
     * about when the envelope was built, not part of its identity.
     *
     * <p>Added because a {@code NotificationEvent} reconstructed from a {@code Signal} (see {@code
     * NotificationSignalMapper}) is necessarily a new instance, and this class previously had only
     * identity equality -- which consumer tests rely on via Mockito's equals-based argument matching.
     * Note the mapper does round-trip {@code timestamp} faithfully, so excluding it here is a
     * deliberate widening rather than a workaround for a lossy translation.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationEvent other)) return false;
        return eventType == other.eventType
                && Objects.equals(projectId, other.projectId)
                && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, projectId, metadata);
    }
}
