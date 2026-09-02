package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The creator's recorded consent to publish one Post, as required by TikTok's Content Sharing Guidelines
 * (MKT-1, V135).
 *
 * <p>One row per Work Item, present only while consent stands: no row means consent was never given, and
 * withdrawing it deletes the row. Re-consenting rewrites the row rather than appending, because the only
 * question anything asks of it is "is consent valid <em>now</em>".
 *
 * <p>{@link #consentHash} is what makes this a record of <em>what</em> was consented to rather than a
 * boolean: it pins the consent to the Post's target set and uploaded media at the moment it was given, so
 * swapping the destination account, changing a privacy level or uploading a different cut silently
 * withdraws it. {@code PublishConsentService} owns how it is computed.
 */
@Entity
@Table(name = "publish_consent")
public class PublishConsent {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItem workItem;

    /** Hex SHA-256 of the target set + uploaded asset set this consent covers. */
    @Column(name = "consent_hash", length = 64, nullable = false)
    private String consentHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consented_by", nullable = false)
    private User consentedBy;

    @Column(name = "consented_at", nullable = false)
    private OffsetDateTime consentedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (consentedAt == null) {
            consentedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public WorkItem getWorkItem() { return workItem; }

    public void setWorkItem(WorkItem workItem) { this.workItem = workItem; }

    public String getConsentHash() { return consentHash; }

    public void setConsentHash(String consentHash) { this.consentHash = consentHash; }

    public User getConsentedBy() { return consentedBy; }

    public void setConsentedBy(User consentedBy) { this.consentedBy = consentedBy; }

    public OffsetDateTime getConsentedAt() { return consentedAt; }

    public void setConsentedAt(OffsetDateTime consentedAt) { this.consentedAt = consentedAt; }
}
