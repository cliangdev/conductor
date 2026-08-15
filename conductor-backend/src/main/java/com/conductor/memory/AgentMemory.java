package com.conductor.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One workspace-scoped memory -- a fact/decision/preference/event an agent has accumulated, distinct
 * from {@code knowledge_pages} (the human-facing wiki) and {@code agent_runs} (per-turn transcripts). A
 * row is live iff {@link #validTo} is null; supersession never mutates content in place, it closes this
 * row ({@code validTo} set, {@link #supersededBy} pointing at the replacement) and inserts a new one, so
 * history stays reconstructible. {@code searchVector} (full-text index) is DB-generated and deliberately
 * not mapped here -- {@link AgentMemoryRepository#search} queries it natively.
 */
@Entity
@Table(name = "agent_memories",
        indexes = {
            @Index(name = "idx_agent_memories_project_live", columnList = "project_id, valid_to"),
            @Index(name = "idx_agent_memories_consolidation", columnList = "status, created_at")
        })
public class AgentMemory {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "agent_id", length = 36)
    private String agentId;

    @Column(name = "source_conversation_id", length = 36)
    private String sourceConversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", length = 20, nullable = false)
    private MemoryType memoryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MemoryStatus status;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "importance", nullable = false)
    private int importance = 5;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    @Column(name = "valid_to")
    private OffsetDateTime validTo;

    @Column(name = "superseded_by", length = 36)
    private String supersededBy;

    @Column(name = "consolidation_attempts", nullable = false)
    private int consolidationAttempts;

    /** Set by {@code MemoryConsolidationService}'s claim step; null means unclaimed. See {@code
     *  AgentMemoryRepository#claimBatch}. */
    @Column(name = "consolidation_claimed_at")
    private OffsetDateTime consolidationClaimedAt;

    @Column(name = "promoted_at")
    private OffsetDateTime promotedAt;

    @Column(name = "last_accessed_at")
    private OffsetDateTime lastAccessedAt;

    @Column(name = "access_count", nullable = false)
    private int accessCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (validFrom == null) {
            validFrom = now;
        }
        if (status == null) {
            status = MemoryStatus.RAW;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(String sourceConversationId) { this.sourceConversationId = sourceConversationId; }

    public MemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(MemoryType memoryType) { this.memoryType = memoryType; }

    public MemoryStatus getStatus() { return status; }
    public void setStatus(MemoryStatus status) { this.status = status; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getImportance() { return importance; }
    public void setImportance(int importance) { this.importance = importance; }

    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }

    public OffsetDateTime getValidTo() { return validTo; }
    public void setValidTo(OffsetDateTime validTo) { this.validTo = validTo; }

    public String getSupersededBy() { return supersededBy; }
    public void setSupersededBy(String supersededBy) { this.supersededBy = supersededBy; }

    public int getConsolidationAttempts() { return consolidationAttempts; }
    public void setConsolidationAttempts(int consolidationAttempts) { this.consolidationAttempts = consolidationAttempts; }

    public OffsetDateTime getConsolidationClaimedAt() { return consolidationClaimedAt; }
    public void setConsolidationClaimedAt(OffsetDateTime consolidationClaimedAt) { this.consolidationClaimedAt = consolidationClaimedAt; }

    public OffsetDateTime getPromotedAt() { return promotedAt; }
    public void setPromotedAt(OffsetDateTime promotedAt) { this.promotedAt = promotedAt; }

    public OffsetDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(OffsetDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }

    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
