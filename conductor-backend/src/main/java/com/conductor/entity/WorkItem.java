package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "work_items")
public class WorkItem {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /**
     * Work Item type as a Workflow-defined string (e.g. {@code PRD}), validated against the bound
     * Workflow's {@code types} at creation. Maps to {@code item_type}; the legacy {@code type} PG-enum
     * column is retained nullable for one release (rolling-deploy safety) and dropped in a follow-up.
     */
    @Column(name = "item_type", length = 64, nullable = false)
    private String type;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "work_item_tasks", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private JsonNode workItemTasks;

    // --- COND-18 Work Item binding (nullable; legacy/unbound rows default to ENGINEERING) ---

    /** The Workflow definition slug this Work Item runs on (e.g. ENGINEERING). */
    @Column(name = "workflow", length = 64)
    private String workflow;

    /** Version of the Workflow this Work Item is pinned to. */
    @Column(name = "workflow_version")
    private Integer workflowVersion;

    /**
     * Current status as a Workflow-defined string (e.g. {@code DRAFT}). This is the authority the engine
     * reads and writes; transitions are validated against the bound Workflow's {@link
     * com.conductor.workflow.lifecycle.Statechart}. The legacy {@code status} PG-enum column is retained
     * nullable for one release (rolling-deploy safety) and dropped in a follow-up.
     */
    @Column(name = "current_status", length = 48, nullable = false)
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
        // current_status is the authority and is set by the application service from the bound
        // Workflow's initial status (statechart-driven), not defaulted here.
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

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

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

    public JsonNode getWorkItemTasks() { return workItemTasks; }
    public void setWorkItemTasks(JsonNode workItemTasks) { this.workItemTasks = workItemTasks; }
}
