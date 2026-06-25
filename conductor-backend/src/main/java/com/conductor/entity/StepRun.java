package com.conductor.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A record of an agent-run Step on a Work Item (COND-18 P0-6) — the legibility data a Review gate renders.
 * Reported by the local skill via MCP. Nested arrays (produced/flags) and the before/after are stored as JSONB.
 */
@Entity
@Table(name = "step_runs")
public class StepRun {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id", nullable = false)
    private Issue issue;

    @Column(name = "workflow", length = 64)
    private String workflow;

    @Column(name = "from_status", length = 48)
    private String fromStatus;

    @Column(name = "to_status", length = 48)
    private String toStatus;

    @Column(name = "step_kind", length = 32, nullable = false)
    private String stepKind;

    @Column(name = "skill", length = 128)
    private String skill;

    @Column(name = "status", length = 24, nullable = false)
    private String status;

    @Column(name = "input_brief", columnDefinition = "TEXT", nullable = false)
    private String inputBrief;

    @Column(name = "reported_by", length = 128, nullable = false)
    private String reportedBy;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "produced", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode produced;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_after", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode beforeAfter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flags", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode flags;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Issue getIssue() { return issue; }
    public void setIssue(Issue issue) { this.issue = issue; }

    public String getWorkflow() { return workflow; }
    public void setWorkflow(String workflow) { this.workflow = workflow; }

    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }

    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }

    public String getStepKind() { return stepKind; }
    public void setStepKind(String stepKind) { this.stepKind = stepKind; }

    public String getSkill() { return skill; }
    public void setSkill(String skill) { this.skill = skill; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInputBrief() { return inputBrief; }
    public void setInputBrief(String inputBrief) { this.inputBrief = inputBrief; }

    public String getReportedBy() { return reportedBy; }
    public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }

    public JsonNode getProduced() { return produced; }
    public void setProduced(JsonNode produced) { this.produced = produced; }

    public JsonNode getBeforeAfter() { return beforeAfter; }
    public void setBeforeAfter(JsonNode beforeAfter) { this.beforeAfter = beforeAfter; }

    public JsonNode getFlags() { return flags; }
    public void setFlags(JsonNode flags) { this.flags = flags; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
