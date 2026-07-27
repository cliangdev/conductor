package com.conductor.integration.ingest;

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
 * One {@link ConnectorFeed}'s change report for one period, with its own narration lifecycle
 * (see {@link DigestStatus}) separate from the feed's pull lifecycle -- a failed or slow narration
 * never blocks the feed's next pull, and every period's history is retained here for audit/replay
 * rather than overwritten. {@code changeReport} is the (later) change-detector's structured output;
 * {@code material} is whether it crossed any {@code MetricSpec}/{@code DimensionSpec} threshold.
 *
 * <p>No FK on {@code knowledgeSourceId}: {@code KnowledgeRetentionService} hard-deletes DEAD
 * {@code knowledge_sources} rows, so a dangling id here is an expected, harmless state rather than an
 * integrity error -- same reasoning {@code KnowledgeDomain.owningAgentSlug} uses.
 */
@Entity
@Table(name = "connector_feed_digest",
        indexes = {
            @Index(name = "idx_connector_feed_digest_status_next_attempt", columnList = "status, next_attempt_at")
        })
public class ConnectorFeedDigest {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "feed_id", length = 36, nullable = false)
    private String feedId;

    /** Non-null is load-bearing: Postgres treats NULLs as distinct, so a null here would defeat the
     *  {@code UNIQUE (feed_id, period_key)} constraint and allow the same period to digest repeatedly. */
    @Column(name = "period_key", length = 32, nullable = false)
    private String periodKey;

    @Column(name = "window_start")
    private OffsetDateTime windowStart;

    @Column(name = "window_end")
    private OffsetDateTime windowEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_report", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> changeReport;

    @Column(name = "material", nullable = false)
    private boolean material = false;

    @Column(name = "dedup_key", length = 128, nullable = false)
    private String dedupKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private DigestStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "narrating_run_id", length = 36)
    private String narratingRunId;

    @Column(name = "knowledge_source_id", length = 36)
    private String knowledgeSourceId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = DigestStatus.PENDING;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getFeedId() { return feedId; }
    public void setFeedId(String feedId) { this.feedId = feedId; }

    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }

    public OffsetDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(OffsetDateTime windowStart) { this.windowStart = windowStart; }

    public OffsetDateTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(OffsetDateTime windowEnd) { this.windowEnd = windowEnd; }

    public Map<String, Object> getChangeReport() { return changeReport; }
    public void setChangeReport(Map<String, Object> changeReport) { this.changeReport = changeReport; }

    public boolean isMaterial() { return material; }
    public void setMaterial(boolean material) { this.material = material; }

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }

    public DigestStatus getStatus() { return status; }
    public void setStatus(DigestStatus status) { this.status = status; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public String getNarratingRunId() { return narratingRunId; }
    public void setNarratingRunId(String narratingRunId) { this.narratingRunId = narratingRunId; }

    public String getKnowledgeSourceId() { return knowledgeSourceId; }
    public void setKnowledgeSourceId(String knowledgeSourceId) { this.knowledgeSourceId = knowledgeSourceId; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
