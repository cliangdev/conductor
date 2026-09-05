package com.conductor.entity;

import java.util.Set;
import java.util.LinkedHashSet;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
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

    /** Null when a machine actor (e.g. an addressable agent via {@code coordinator:create_work_item})
     *  authored this item -- {@link #createdByLabel} carries its identity instead. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_by_label", length = 255)
    private String createdByLabel;

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

    // --- Generic per-item scheduling (V130). Workflow-agnostic: any Workflow can put an item on a clock. ---

    /** When this Work Item is due, as an absolute instant. Null when the item is not scheduled. */
    @Column(name = "scheduled_for")
    private OffsetDateTime scheduledFor;

    /**
     * IANA zone id the schedule was authored in (e.g. {@code America/New_York}), kept alongside the
     * absolute instant so a local-wall-clock reading of the schedule survives DST. Null when unscheduled
     * or when the author expressed no zone.
     */
    @Column(name = "schedule_timezone", length = 64)
    private String scheduleTimezone;

    /**
     * Freeform labels, for grouping work across type, status and Workflow.
     *
     * <p>An {@code @ElementCollection} rather than an entity: a tag has no identity beyond its own text,
     * and nothing hangs off it. Eagerly fetched because every surface that lists a Work Item shows them —
     * a lazy set here is an N+1 on the list page, which is the one place tags earn their keep.
     *
     * <p>Normalised to lower case on write (see {@code WorkItemService}), so "Autumn" and "autumn" are one
     * tag rather than two that look identical in a filter list.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "work_item_tag", joinColumns = @JoinColumn(name = "work_item_id"))
    @Column(name = "tag", length = 64, nullable = false)
    private Set<String> tags = new LinkedHashSet<>();

    /**
     * The review round currently open on this item (COND-23, V134). Starts at 0 and is bumped whenever a
     * CHANGES_REQUESTED verdict routes the item out of a review status: an APPROVED {@code Review} stamped
     * with an earlier round no longer satisfies the gate, so an approval cast before a rejection cannot let
     * the item through on resubmission. Workflows with no changes-requested lane (ENGINEERING) never leave
     * round 0, which is why their gating is untouched.
     */
    @Column(name = "current_review_round", nullable = false)
    private int currentReviewRound = 0;

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

    public String getCreatedByLabel() { return createdByLabel; }
    public void setCreatedByLabel(String createdByLabel) { this.createdByLabel = createdByLabel; }

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

    public OffsetDateTime getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(OffsetDateTime scheduledFor) { this.scheduledFor = scheduledFor; }

    public String getScheduleTimezone() { return scheduleTimezone; }
    public void setScheduleTimezone(String scheduleTimezone) { this.scheduleTimezone = scheduleTimezone; }

    public int getCurrentReviewRound() { return currentReviewRound; }
    public void setCurrentReviewRound(int currentReviewRound) { this.currentReviewRound = currentReviewRound; }

    public JsonNode getWorkItemTasks() { return workItemTasks; }
    public void setWorkItemTasks(JsonNode workItemTasks) { this.workItemTasks = workItemTasks; }
    public Set<String> getTags() { return tags; }

    public void setTags(Set<String> tags) { this.tags = tags == null ? new LinkedHashSet<>() : tags; }

}
