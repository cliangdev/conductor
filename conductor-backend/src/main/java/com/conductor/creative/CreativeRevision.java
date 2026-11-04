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
 * One recorded change to a CAROUSEL Creative's slideshow spec: a snapshot of the spec (with the slides,
 * alt text and style it was applied with) and the editable text that changed. Written in the same
 * transaction as the Creative save. See {@code V174__creative_slideshow_spec.sql}.
 */
@Entity
@Table(name = "creative_revision")
public class CreativeRevision {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String SOURCE_EDITOR = "EDITOR";
    public static final String SOURCE_REVIEWER = "REVIEWER";
    public static final String SOURCE_MCP = "MCP";
    /** A spec set on a Creative that had none. */
    public static final String SOURCE_IMPORT = "IMPORT";
    /** A spec set at create, or replacing an existing one. */
    public static final String SOURCE_COMMIT = "COMMIT";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "creative_id", length = 36, nullable = false)
    private String creativeId;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Column(name = "creative_version", nullable = false)
    private int creativeVersion;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", columnDefinition = "jsonb")
    private JsonNode spec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slides", columnDefinition = "jsonb")
    private JsonNode slides;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slide_alt_text", columnDefinition = "jsonb")
    private JsonNode slideAltText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "slide_style", columnDefinition = "jsonb")
    private JsonNode slideStyle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "text_diff", columnDefinition = "jsonb", nullable = false)
    private JsonNode textDiff;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", columnDefinition = "jsonb")
    private JsonNode checks;

    @Column(name = "author_user_id", length = 36)
    private String authorUserId;

    @Column(name = "post_id", length = 36)
    private String postId;

    @Column(name = "review_round")
    private Integer reviewRound;

    @Column(name = "workflow_run_id", length = 36)
    private String workflowRunId;

    @Column(name = "render_id", length = 36)
    private String renderId;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "applied_at")
    private OffsetDateTime appliedAt;

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

    public String getCreativeId() { return creativeId; }
    public void setCreativeId(String creativeId) { this.creativeId = creativeId; }

    public int getRevisionNo() { return revisionNo; }
    public void setRevisionNo(int revisionNo) { this.revisionNo = revisionNo; }

    public int getCreativeVersion() { return creativeVersion; }
    public void setCreativeVersion(int creativeVersion) { this.creativeVersion = creativeVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public JsonNode getSpec() { return spec; }
    public void setSpec(JsonNode spec) { this.spec = spec; }

    public JsonNode getSlides() { return slides; }
    public void setSlides(JsonNode slides) { this.slides = slides; }

    public JsonNode getSlideAltText() { return slideAltText; }
    public void setSlideAltText(JsonNode slideAltText) { this.slideAltText = slideAltText; }

    public JsonNode getSlideStyle() { return slideStyle; }
    public void setSlideStyle(JsonNode slideStyle) { this.slideStyle = slideStyle; }

    public JsonNode getTextDiff() { return textDiff; }
    public void setTextDiff(JsonNode textDiff) { this.textDiff = textDiff; }

    public JsonNode getChecks() { return checks; }
    public void setChecks(JsonNode checks) { this.checks = checks; }

    public String getAuthorUserId() { return authorUserId; }
    public void setAuthorUserId(String authorUserId) { this.authorUserId = authorUserId; }

    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public Integer getReviewRound() { return reviewRound; }
    public void setReviewRound(Integer reviewRound) { this.reviewRound = reviewRound; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getRenderId() { return renderId; }
    public void setRenderId(String renderId) { this.renderId = renderId; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(OffsetDateTime appliedAt) { this.appliedAt = appliedAt; }
}
