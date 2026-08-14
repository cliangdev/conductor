package com.conductor.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One turn in a {@link Conversation}'s ordered log. {@code agentRunId} is a soft link (no FK) to the
 * {@code agent_runs} row an ASSISTANT turn produced -- same non-FK convention as
 * {@code agent_runs.workflow_run_id}, since a run can outlive the conversation that started it.
 */
@Entity
@Table(name = "conversation_messages")
public class ConversationMessage {

    /** Who spoke this turn. */
    public enum Role { USER, ASSISTANT }

    /** Lifecycle of one turn -- an ASSISTANT turn starts PENDING while {@link AgentConversationRunner}
     *  is still generating it; USER turns are always inserted already COMPLETED. */
    public enum Status { PENDING, COMPLETED, FAILED }

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "conversation_id", length = 36, nullable = false)
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    @Column(name = "agent_run_id", length = 36)
    private String agentRunId;

    @Column(name = "external_message_id", length = 100)
    private String externalMessageId;

    @Column(name = "author_label", length = 100)
    private String authorLabel;

    @Column(name = "error_reason", length = 500)
    private String errorReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = Status.COMPLETED;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getAgentRunId() { return agentRunId; }
    public void setAgentRunId(String agentRunId) { this.agentRunId = agentRunId; }

    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }

    public String getAuthorLabel() { return authorLabel; }
    public void setAuthorLabel(String authorLabel) { this.authorLabel = authorLabel; }

    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
