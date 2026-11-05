package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One acceptance criterion on a Work Item. {@code kind} says who verifies it: {@code auto} (a test or an
 * agent can) or {@code manual} (a person checks). {@code externalRef} optionally carries the id it has in the
 * PRD (such as {@code AC-P0-1.1}). Marking it met records who and when.
 */
@Entity
@Table(name = "work_item_criteria")
public class WorkItemCriterion {

    public static final String KIND_AUTO = "auto";
    public static final String KIND_MANUAL = "manual";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "work_item_id", length = 36, nullable = false, updatable = false)
    private String workItemId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "text", columnDefinition = "TEXT", nullable = false)
    private String text;

    /** {@link #KIND_AUTO} or {@link #KIND_MANUAL}. */
    @Column(name = "kind", length = 16, nullable = false)
    private String kind = KIND_AUTO;

    @Column(name = "external_ref", length = 32)
    private String externalRef;

    @Column(name = "met", nullable = false)
    private boolean met;

    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "met_at")
    private OffsetDateTime metAt;

    @Column(name = "met_by_label", length = 128)
    private String metByLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }

    public boolean isMet() { return met; }
    public void setMet(boolean met) { this.met = met; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }

    public OffsetDateTime getMetAt() { return metAt; }
    public void setMetAt(OffsetDateTime metAt) { this.metAt = metAt; }

    public String getMetByLabel() { return metByLabel; }
    public void setMetByLabel(String metByLabel) { this.metByLabel = metByLabel; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
