package com.conductor.marketing.links;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An App Store campaign link of one workspace: a Post's, a bio's, a creator's or an email's. See V189. */
@Entity
@Table(name = "tracked_link")
public class TrackedLink {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    /** POST, BIO, CREATOR, EMAIL or OTHER. */
    @Column(name = "kind", length = 16, nullable = false, updatable = false)
    private String kind;

    @Column(name = "family", length = 64, nullable = false, updatable = false)
    private String family;

    /** The {@code ct} of the URL; the metric dimension is {@code ct:{token}}. */
    @Column(name = "token", length = 40, nullable = false, updatable = false)
    private String token;

    @Column(name = "url", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String url;

    @Column(name = "work_item_id", length = 36, updatable = false)
    private String workItemId;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "created_by", length = 36, updatable = false)
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
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
