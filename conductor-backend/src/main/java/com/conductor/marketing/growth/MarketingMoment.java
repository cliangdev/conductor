package com.conductor.marketing.growth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A dated seasonal moment of a workspace, with how many days ahead its surfaces need attention. See V195. */
@Entity
@Table(name = "marketing_moment")
public class MarketingMoment {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "key", length = 64, nullable = false)
    private String momentKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "date", nullable = false)
    private LocalDate momentDate;

    @Column(name = "lead_days", nullable = false)
    private int leadDays = 21;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void assign() {
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
    public String getMomentKey() { return momentKey; }
    public void setMomentKey(String momentKey) { this.momentKey = momentKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getMomentDate() { return momentDate; }
    public void setMomentDate(LocalDate momentDate) { this.momentDate = momentDate; }
    public int getLeadDays() { return leadDays; }
    public void setLeadDays(int leadDays) { this.leadDays = leadDays; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
