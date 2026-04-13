package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workflow_step_runs")
public class WorkflowStepRun {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_run_id", nullable = false)
    private WorkflowJobRun jobRun;

    @Column(name = "step_id", length = 255)
    private String stepId;

    @Column(name = "step_name", length = 255, nullable = false)
    private String stepName;

    @Column(name = "step_type", length = 64, nullable = false)
    private String stepType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "workflow_step_status")
    @ColumnTransformer(write = "?::workflow_step_status")
    private WorkflowStepStatus status;

    @Column(name = "log", columnDefinition = "TEXT")
    private String log;

    @Column(name = "output_json", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String outputJson;

    @Column(name = "error_reason", columnDefinition = "TEXT")
    private String errorReason;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = WorkflowStepStatus.PENDING;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public WorkflowJobRun getJobRun() { return jobRun; }
    public void setJobRun(WorkflowJobRun jobRun) { this.jobRun = jobRun; }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }

    public WorkflowStepStatus getStatus() { return status; }
    public void setStatus(WorkflowStepStatus status) { this.status = status; }

    public String getLog() { return log; }
    public void setLog(String log) { this.log = log; }

    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String outputJson) { this.outputJson = outputJson; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
