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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;

@Entity
@Table(name = "issues")
public class Issue {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, columnDefinition = "issue_type")
    @ColumnTransformer(write = "?::issue_type")
    private IssueType type;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "issue_status")
    @ColumnTransformer(write = "?::issue_status")
    private IssueStatus status;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private Integer sequenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "github_pr_url", length = 512)
    private String githubPrUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issue_tasks", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode issueTasks;

    // --- COND-18 Work Item binding (nullable; legacy/unbound rows default to ENGINEERING) ---

    /** The Workflow definition slug this Work Item runs on (e.g. ENGINEERING). */
    @Column(name = "workflow", length = 64)
    private String workflow;

    /** Version of the Workflow this Work Item is pinned to. */
    @Column(name = "workflow_version")
    private Integer workflowVersion;

    /** Current status as a Workflow-defined string (mirrors {@link #status} for ENGINEERING-bound issues). */
    @Column(name = "current_status", length = 48)
    private String currentStatus;

    /** Per-Work-Item engine scratch (step outputs, guard inputs). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_context", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode stateContext;

    /** Parent Work Item for fan-out (create-sub-items) children. */
    @Column(name = "parent_work_item_id", length = 36)
    private String parentWorkItemId;

    /** Append-only Outcome Metric series ({value, observedAt, note}); metric def from the Workflow (COND-18 E6). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outcome_metric", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode outcomeMetric;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = IssueStatus.DRAFT;
        }
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public IssueType getType() { return type; }
    public void setType(IssueType type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public IssueStatus getStatus() { return status; }
    public void setStatus(IssueStatus status) { this.status = status; }

    public Integer getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public User getAssignee() { return assignee; }
    public void setAssignee(User assignee) { this.assignee = assignee; }

    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getGithubPrUrl() { return githubPrUrl; }
    public void setGithubPrUrl(String githubPrUrl) { this.githubPrUrl = githubPrUrl; }

    public String getWorkflow() { return workflow; }
    public void setWorkflow(String workflow) { this.workflow = workflow; }

    public Integer getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(Integer workflowVersion) { this.workflowVersion = workflowVersion; }

    public String getCurrentStatus() { return currentStatus; }
    public void setCurrentStatus(String currentStatus) { this.currentStatus = currentStatus; }

    public JsonNode getStateContext() { return stateContext; }
    public void setStateContext(JsonNode stateContext) { this.stateContext = stateContext; }

    public String getParentWorkItemId() { return parentWorkItemId; }
    public void setParentWorkItemId(String parentWorkItemId) { this.parentWorkItemId = parentWorkItemId; }

    public JsonNode getOutcomeMetric() { return outcomeMetric; }
    public void setOutcomeMetric(JsonNode outcomeMetric) { this.outcomeMetric = outcomeMetric; }

    public JsonNode getIssueTasks() { return issueTasks; }
    public void setIssueTasks(JsonNode issueTasks) { this.issueTasks = issueTasks; }
}
