package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "reviews",
    uniqueConstraints = @UniqueConstraint(columnNames = {"work_item_id", "reviewer_id"})
)
public class Review {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "work_item_id", length = 36, nullable = false)
    private String workItemId;

    @Column(name = "reviewer_id", length = 36, nullable = false)
    private String reviewerId;

    @Column(name = "verdict", length = 32, nullable = false)
    private String verdict;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;

    /**
     * Hex SHA-256 of the publish bundle this verdict was cast against (COND-23), computed by
     * {@code PublishBundleHasher}. An APPROVED review satisfies its gate only while the Work Item still
     * hashes to this value, so editing the caption, targets, fire time or media revokes the approval.
     *
     * <p>Null means "not bundle-bound" and is the norm: pre-existing reviews and every review on an item
     * with no publish targets (all of ENGINEERING) leave it null and gate exactly as they did before.
     */
    @Column(name = "bundle_hash", length = 64)
    private String bundleHash;

    /**
     * The Work Item's {@code currentReviewRound} when this verdict was cast. A round closes whenever a
     * CHANGES_REQUESTED verdict routes the item out of review, which is what stops one reviewer's earlier
     * approval from satisfying the gate after another reviewer sent the item back. Null on pre-existing
     * reviews, which are treated as belonging to whatever round is current.
     */
    @Column(name = "review_round")
    private Integer reviewRound;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }

    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(OffsetDateTime submittedAt) { this.submittedAt = submittedAt; }

    public String getBundleHash() { return bundleHash; }
    public void setBundleHash(String bundleHash) { this.bundleHash = bundleHash; }

    public Integer getReviewRound() { return reviewRound; }
    public void setReviewRound(Integer reviewRound) { this.reviewRound = reviewRound; }
}
