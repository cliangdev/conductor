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

@Entity
@Table(name = "comment_replies")
public class CommentReply {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id", nullable = false)
    private Comment comment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(name = "author_label", columnDefinition = "TEXT")
    private String authorLabel;

    @Column(name = "author_agent_id", length = 36)
    private String authorAgentId;

    @Column(name = "edited_at")
    private OffsetDateTime editedAt;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Comment getComment() { return comment; }
    public void setComment(Comment comment) { this.comment = comment; }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }

    public String getAuthorLabel() { return authorLabel; }
    public void setAuthorLabel(String authorLabel) { this.authorLabel = authorLabel; }

    public String getAuthorAgentId() { return authorAgentId; }
    public void setAuthorAgentId(String authorAgentId) { this.authorAgentId = authorAgentId; }

    public OffsetDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(OffsetDateTime editedAt) { this.editedAt = editedAt; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
