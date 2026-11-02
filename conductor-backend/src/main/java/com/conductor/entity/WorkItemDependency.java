package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A directed link between two Work Items of one project. A row reads "{@code workItemId} KIND
 * {@code relatedWorkItemId}": {@code A BLOCKS B} means B cannot start until A is finished, {@code C
 * DISCOVERED_FROM D} means C came up while working on D. Only these two kinds exist; the inverse
 * ({@code blockedBy}, {@code discovered}) is a read projection, never a second row.
 *
 * <p>Ids are plain columns rather than associations: dependencies are read in batches by id and never walked
 * lazily.
 */
@Entity
@Table(name = "work_item_dependencies")
public class WorkItemDependency {

    public static final String KIND_BLOCKS = "BLOCKS";
    public static final String KIND_DISCOVERED_FROM = "DISCOVERED_FROM";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "work_item_id", length = 36, nullable = false, updatable = false)
    private String workItemId;

    @Column(name = "related_work_item_id", length = 36, nullable = false, updatable = false)
    private String relatedWorkItemId;

    /** {@link #KIND_BLOCKS} or {@link #KIND_DISCOVERED_FROM}. */
    @Column(name = "kind", length = 24, nullable = false, updatable = false)
    private String kind;

    /** The user who added it; null for a machine actor, whose identity is {@link #createdByLabel}. */
    @Column(name = "created_by", length = 36, updatable = false)
    private String createdBy;

    @Column(name = "created_by_label", length = 128, updatable = false)
    private String createdByLabel;

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

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }

    public String getRelatedWorkItemId() { return relatedWorkItemId; }
    public void setRelatedWorkItemId(String relatedWorkItemId) { this.relatedWorkItemId = relatedWorkItemId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getCreatedByLabel() { return createdByLabel; }
    public void setCreatedByLabel(String createdByLabel) { this.createdByLabel = createdByLabel; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
