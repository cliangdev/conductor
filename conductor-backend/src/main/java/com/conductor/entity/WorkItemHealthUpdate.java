package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One health update on a Work Item: {@code ON_TRACK}, {@code AT_RISK} or {@code OFF_TRACK} with a note. An
 * update is a <em>draft</em> until a person confirms it ({@code confirmedAt} set); the item's current health
 * is its latest confirmed update. {@code draftedByLabel} says who proposed it (an agent label or a person's
 * name); a person who records an update directly confirms it in the same write.
 */
@Entity
@Table(name = "work_item_health_updates")
public class WorkItemHealthUpdate {

    public static final String ON_TRACK = "ON_TRACK";
    public static final String AT_RISK = "AT_RISK";
    public static final String OFF_TRACK = "OFF_TRACK";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "work_item_id", length = 36, nullable = false, updatable = false)
    private String workItemId;

    @Column(name = "health", length = 12, nullable = false)
    private String health;

    @Column(name = "note", columnDefinition = "TEXT", nullable = false)
    private String note;

    @Column(name = "drafted_by_label", length = 255)
    private String draftedByLabel;

    @Column(name = "confirmed_by", length = 36)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

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

    public boolean isConfirmed() { return confirmedAt != null; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }

    public String getHealth() { return health; }
    public void setHealth(String health) { this.health = health; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getDraftedByLabel() { return draftedByLabel; }
    public void setDraftedByLabel(String draftedByLabel) { this.draftedByLabel = draftedByLabel; }

    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }

    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
