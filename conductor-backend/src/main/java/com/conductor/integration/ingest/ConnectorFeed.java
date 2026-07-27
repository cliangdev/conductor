package com.conductor.integration.ingest;

import com.conductor.integration.IngestMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One connection's binding to one declared {@code IngestSpec} feed -- the row that turns a connector's
 * JSON-declared feed into something that actually pulls on a schedule. Carries both the
 * connector-owned {@code cursorState} (opaque, never parsed by the platform -- see
 * {@code com.conductor.integration.IngestBatch}) and the platform-owned scheduling/backoff state
 * ({@code nextRunAt}, {@code consecutiveFailures}, ...) and change-detection baseline
 * ({@code lastStats}, the exact opposite of {@code cursorState}: parsed JSON the (later) aggregator
 * reads and writes every run). Digest narration lifecycle lives separately in
 * {@link ConnectorFeedDigest} so a failed narration never blocks this feed's next pull.
 */
@Entity
@Table(name = "connector_feed",
        indexes = {
            @Index(name = "idx_connector_feed_due", columnList = "status, enabled, next_run_at"),
            @Index(name = "idx_connector_feed_project_connector", columnList = "project_id, connector_id")
        })
public class ConnectorFeed {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "connection_id", length = 36, nullable = false)
    private String connectionId;

    @Column(name = "connector_id", length = 64, nullable = false)
    private String connectorId;

    /** Non-null is load-bearing: Postgres treats NULLs as distinct, so a null here would defeat the
     *  {@code UNIQUE (connection_id, ingest_id)} constraint and permit duplicate feeds. */
    @Column(name = "ingest_id", length = 64, nullable = false)
    private String ingestId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16, nullable = false)
    private IngestMode mode = IngestMode.SNAPSHOT;

    @Column(name = "interval_minutes", nullable = false)
    private int intervalMinutes = 1440;

    /** Opaque, connector-owned. See the class javadoc -- the platform never parses this. */
    @Column(name = "cursor_state", columnDefinition = "TEXT")
    private String cursorState;

    @Column(name = "cursor_updated_at")
    private OffsetDateTime cursorUpdatedAt;

    @Column(name = "last_window_start")
    private OffsetDateTime lastWindowStart;

    @Column(name = "last_window_end")
    private OffsetDateTime lastWindowEnd;

    /** Platform-owned, parsed statistical baseline (rolling mean/stddev per metric) the change-detector
     *  reads and writes every run -- the exact opposite of {@link #cursorState}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_stats", columnDefinition = "jsonb")
    private Map<String, Object> lastStats;

    @Column(name = "quiet_periods", nullable = false)
    private int quietPeriods;

    @Column(name = "next_run_at", nullable = false)
    private OffsetDateTime nextRunAt;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "last_success_at")
    private OffsetDateTime lastSuccessAt;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ConnectorFeedStatus status;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = ConnectorFeedStatus.ACTIVE;
        }
        if (nextRunAt == null) {
            nextRunAt = OffsetDateTime.now();
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public String getIngestId() { return ingestId; }
    public void setIngestId(String ingestId) { this.ingestId = ingestId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public IngestMode getMode() { return mode; }
    public void setMode(IngestMode mode) { this.mode = mode; }

    public int getIntervalMinutes() { return intervalMinutes; }
    public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }

    public String getCursorState() { return cursorState; }
    public void setCursorState(String cursorState) { this.cursorState = cursorState; }

    public OffsetDateTime getCursorUpdatedAt() { return cursorUpdatedAt; }
    public void setCursorUpdatedAt(OffsetDateTime cursorUpdatedAt) { this.cursorUpdatedAt = cursorUpdatedAt; }

    public OffsetDateTime getLastWindowStart() { return lastWindowStart; }
    public void setLastWindowStart(OffsetDateTime lastWindowStart) { this.lastWindowStart = lastWindowStart; }

    public OffsetDateTime getLastWindowEnd() { return lastWindowEnd; }
    public void setLastWindowEnd(OffsetDateTime lastWindowEnd) { this.lastWindowEnd = lastWindowEnd; }

    public Map<String, Object> getLastStats() { return lastStats; }
    public void setLastStats(Map<String, Object> lastStats) { this.lastStats = lastStats; }

    public int getQuietPeriods() { return quietPeriods; }
    public void setQuietPeriods(int quietPeriods) { this.quietPeriods = quietPeriods; }

    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public OffsetDateTime getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(OffsetDateTime lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }

    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }

    public ConnectorFeedStatus getStatus() { return status; }
    public void setStatus(ConnectorFeedStatus status) { this.status = status; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
