package com.conductor.marketing.promises;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The message one target (creative, product page, keyword, paywall headline or Post) makes. See V194. */
@Entity
@Table(name = "marketing_message_tag")
public class MarketingMessageTag {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "message", length = 120, nullable = false)
    private String message;

    /** CREATIVE, PRODUCT_PAGE, KEYWORD, PAYWALL_HEADLINE or POST. */
    @Column(name = "target_kind", length = 24, nullable = false, updatable = false)
    private String targetKind;

    @Column(name = "target_ref", length = 200, nullable = false, updatable = false)
    private String targetRef;

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
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTargetKind() { return targetKind; }
    public void setTargetKind(String targetKind) { this.targetKind = targetKind; }
    public String getTargetRef() { return targetRef; }
    public void setTargetRef(String targetRef) { this.targetRef = targetRef; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
