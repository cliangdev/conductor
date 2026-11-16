package com.conductor.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A person asked an agent, with an {@code @agent} mention, to act on a comment thread. Its status is derived from
 * the workflow run ({@link #getWorkflowRunId()}); {@link #getError()} is set only when no run could be started.
 */
@Entity
@Table(name = "comment_agent_requests")
public class CommentAgentRequest {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "comment_id", length = 36, nullable = false, updatable = false)
    private String commentId;

    @Column(name = "reply_id", length = 36)
    private String replyId;

    @Column(name = "agent_id", length = 36, nullable = false, updatable = false)
    private String agentId;

    @Column(name = "workflow_run_id", length = 36)
    private String workflowRunId;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

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

    public String getCommentId() { return commentId; }
    public void setCommentId(String commentId) { this.commentId = commentId; }

    public String getReplyId() { return replyId; }
    public void setReplyId(String replyId) { this.replyId = replyId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
