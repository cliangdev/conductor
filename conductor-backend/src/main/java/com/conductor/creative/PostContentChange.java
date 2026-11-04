package com.conductor.creative;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One move of a Post onto a re-rendered Creative: written by {@link CreativeEditPropagator} when a slide-text
 * revision reaches a Post that uses the Creative. Carries what the review page needs to say "changed since you
 * last looked": the revision, the text diff, who made it, and the Post's status and review round before and
 * after. See {@code V176__post_content_change.sql}.
 */
@Entity
@Table(name = "post_content_change")
public class PostContentChange {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "work_item_id", length = 36, nullable = false)
    private String workItemId;

    @Column(name = "creative_id", length = 36, nullable = false)
    private String creativeId;

    @Column(name = "revision_id", length = 36, nullable = false)
    private String revisionId;

    /** The revision's source: EDITOR, REVIEWER or MCP. */
    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @Column(name = "actor_user_id", length = 36)
    private String actorUserId;

    @Column(name = "from_status", length = 64)
    private String fromStatus;

    @Column(name = "to_status", length = 64)
    private String toStatus;

    @Column(name = "review_round_before", nullable = false)
    private int reviewRoundBefore;

    @Column(name = "review_round_after", nullable = false)
    private int reviewRoundAfter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "text_diff", columnDefinition = "jsonb", nullable = false)
    private JsonNode textDiff;

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

    public String getCreativeId() { return creativeId; }
    public void setCreativeId(String creativeId) { this.creativeId = creativeId; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }

    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }

    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }

    public int getReviewRoundBefore() { return reviewRoundBefore; }
    public void setReviewRoundBefore(int reviewRoundBefore) { this.reviewRoundBefore = reviewRoundBefore; }

    public int getReviewRoundAfter() { return reviewRoundAfter; }
    public void setReviewRoundAfter(int reviewRoundAfter) { this.reviewRoundAfter = reviewRoundAfter; }

    public JsonNode getTextDiff() { return textDiff; }
    public void setTextDiff(JsonNode textDiff) { this.textDiff = textDiff; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
