package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow_schedule_skips")
public class WorkflowScheduleSkip {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WorkflowSchedule schedule;

    @Column(name = "skipped_at", nullable = false)
    private OffsetDateTime skippedAt;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "run_id", length = 36)
    private String runId;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (skippedAt == null) {
            skippedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public WorkflowSchedule getSchedule() { return schedule; }
    public void setSchedule(WorkflowSchedule schedule) { this.schedule = schedule; }

    public OffsetDateTime getSkippedAt() { return skippedAt; }
    public void setSkippedAt(OffsetDateTime skippedAt) { this.skippedAt = skippedAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
}
