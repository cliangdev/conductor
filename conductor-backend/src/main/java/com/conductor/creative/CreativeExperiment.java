package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A hook experiment (COND-24 T5): two or more variants of one Creative family, each published as its
 * own Post, decided by {@link CreativeExperimentService#decide} once every variant has a performance
 * snapshot at or after {@code fireTime + windowHours}. See {@code V143__creative_experiment.sql} and
 * {@code docs/creatives.md}'s "Performance and experiments" section.
 */
@Entity
@Table(name = "creative_experiment")
public class CreativeExperiment {

    public static final String METRIC_VIEWS = "views";
    public static final String METRIC_ENGAGEMENT_RATE = "engagement_rate";
    public static final String METRIC_AVG_VIEW_PCT = "avg_view_pct";

    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_DECIDED = "DECIDED";
    public static final String STATE_INCONCLUSIVE = "INCONCLUSIVE";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    /** The family's root Creative (variant letter "a") — see {@link Creative#getParentCreativeId()}. */
    @Column(name = "parent_creative_id", length = 36, nullable = false)
    private String parentCreativeId;

    @Column(name = "metric", length = 24, nullable = false)
    private String metric;

    @Column(name = "window_hours", nullable = false)
    private int windowHours;

    @Column(name = "state", length = 16, nullable = false)
    private String state;

    @Column(name = "winner_creative_id", length = 36)
    private String winnerCreativeId;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "jsonb")
    private JsonNode summary;

    @Column(name = "winner_line_confirmed_at")
    private OffsetDateTime winnerLineConfirmedAt;

    @Column(name = "winner_line_confirmed_by", length = 36)
    private String winnerLineConfirmedBy;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (state == null) {
            state = STATE_RUNNING;
        }
        if (metric == null) {
            metric = METRIC_VIEWS;
        }
        if (windowHours <= 0) {
            windowHours = 72;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public boolean isRunning() {
        return STATE_RUNNING.equals(state);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getParentCreativeId() { return parentCreativeId; }
    public void setParentCreativeId(String parentCreativeId) { this.parentCreativeId = parentCreativeId; }

    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }

    public int getWindowHours() { return windowHours; }
    public void setWindowHours(int windowHours) { this.windowHours = windowHours; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getWinnerCreativeId() { return winnerCreativeId; }
    public void setWinnerCreativeId(String winnerCreativeId) { this.winnerCreativeId = winnerCreativeId; }

    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }

    public JsonNode getSummary() { return summary; }
    public void setSummary(JsonNode summary) { this.summary = summary; }

    public OffsetDateTime getWinnerLineConfirmedAt() { return winnerLineConfirmedAt; }
    public void setWinnerLineConfirmedAt(OffsetDateTime winnerLineConfirmedAt) { this.winnerLineConfirmedAt = winnerLineConfirmedAt; }

    public String getWinnerLineConfirmedBy() { return winnerLineConfirmedBy; }
    public void setWinnerLineConfirmedBy(String winnerLineConfirmedBy) { this.winnerLineConfirmedBy = winnerLineConfirmedBy; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
