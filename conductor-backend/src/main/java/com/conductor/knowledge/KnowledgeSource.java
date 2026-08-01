package com.conductor.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One row in the unified ingestion inbox -- a piece of inbound material (Slack message, GitHub PR,
 * manual note, ...) offered to the Knowledge Center, by value ({@code payload}) or by reference
 * ({@code sourceRef}, resolved later by an ingestion adapter). Large payloads are offloaded to
 * {@link com.conductor.service.StorageService} and referenced via {@code payloadUri} instead of stored
 * inline. See {@link KnowledgeIngestionService} for the write path.
 */
@Entity
@Table(name = "knowledge_sources",
        indexes = {
            @Index(name = "idx_knowledge_sources_status", columnList = "status, next_attempt_at"),
            @Index(name = "idx_knowledge_sources_project_status", columnList = "project_id, status")
        })
public class KnowledgeSource {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "source_type", length = 100, nullable = false)
    private String sourceType;

    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    /** The domain lane (a {@code KnowledgeDomain} slug) this source was routed to at submit time by
     *  {@code KnowledgeDomainResolver} -- null is the generalist/unclassified lane, not "not yet
     *  resolved" (resolution always runs before insert). Stamped once; never re-resolved after the fact
     *  even if the registry changes later. */
    @Column(name = "domain", length = 64)
    private String domain;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_uri", length = 512)
    private String payloadUri;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "origin", columnDefinition = "jsonb")
    private Map<String, Object> origin;

    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "dedup_key", length = 128, nullable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private KnowledgeSourceStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "processing_run_id", length = 36)
    private String processingRunId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Set alongside {@code status = SKIPPED} -- why the librarian judged this source not worth a
     *  page, e.g. "duplicate of existing page" or "not material". Null for every other status. */
    @Column(name = "skip_reason", columnDefinition = "TEXT")
    private String skipReason;

    /** Set by {@code KnowledgeRetentionService} when this row's payload has been compacted (inline
     *  {@code payload} nulled and any offloaded GCS object deleted) -- independent of a DEAD row's
     *  later hard deletion. Null means "not yet compacted". */
    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (receivedAt == null) {
            receivedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = KnowledgeSourceStatus.PENDING;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getPayloadUri() { return payloadUri; }
    public void setPayloadUri(String payloadUri) { this.payloadUri = payloadUri; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Map<String, Object> getOrigin() { return origin; }
    public void setOrigin(Map<String, Object> origin) { this.origin = origin; }

    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }

    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }

    public KnowledgeSourceStatus getStatus() { return status; }
    public void setStatus(KnowledgeSourceStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getProcessingRunId() { return processingRunId; }
    public void setProcessingRunId(String processingRunId) { this.processingRunId = processingRunId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }

    public OffsetDateTime getPurgedAt() { return purgedAt; }
    public void setPurgedAt(OffsetDateTime purgedAt) { this.purgedAt = purgedAt; }
}
