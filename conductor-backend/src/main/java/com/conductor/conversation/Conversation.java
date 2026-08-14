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
 * A running back-and-forth between a human (or an external channel like Discord) and one addressable
 * {@link com.conductor.agent.Agent} -- the container {@link AgentConversationRunner} drives turns into,
 * and {@link ConversationMessage} rows log. {@code channel}/{@code channelKey} identify where the
 * conversation lives ({@code api} has no key; {@code discord} packs {@code '<guild_id>:<thread_id>'}) --
 * the V110 migration's partial unique index on (project_id, channel, channel_key) is the real
 * at-most-one-conversation-per-channel-key guard, not this entity.
 *
 * <p>Mirrors {@link com.conductor.agent.Agent}/{@link com.conductor.agent.run.AgentRun}'s style: UUID
 * string id, {@link OffsetDateTime} timestamps, JSON columns kept as strings and (de)serialized via the
 * shared {@code ObjectMapper} rather than typed/mapped.
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    /** Lifecycle status of a conversation. The DB's {@code ck_conversations_status} CHECK still allows
     *  {@code 'ARCHIVED'} (an intentionally untouched historical migration), but nothing writes it --
     *  {@code archive()} was removed as dead code, so this enum only ever holds {@code ACTIVE} today. */
    public enum Status { ACTIVE }

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "agent_id", length = 36, nullable = false)
    private String agentId;

    /** {@code "api"} or {@code "discord"} -- see {@link ConversationChannel#dbValue()}. Kept as a plain
     *  String column (mirrors {@code Agent.provider}) rather than a JPA-enumerated one, since the DB
     *  value is lowercase and {@code @Enumerated(STRING)} would persist the enum constant's name
     *  (uppercase) instead. */
    @Column(name = "channel", length = 20, nullable = false)
    private String channel;

    @Column(name = "channel_key", length = 200)
    private String channelKey;

    @Column(name = "title", length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private Status status;

    /** Null for a machine actor -- {@link #createdByLabel} carries its identity instead. Attribution
     *  follows {@code project_docs}' user-or-label pattern; the V110 CHECK constraint is the actual
     *  guarantee that a byline is always present one way or the other. */
    @Column(name = "created_by_user_id", length = 36)
    private String createdByUserId;

    @Column(name = "created_by_label", length = 255)
    private String createdByLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_message_at", nullable = false)
    private OffsetDateTime lastMessageAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = Status.ACTIVE;
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastMessageAt == null) {
            lastMessageAt = now;
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getChannelKey() { return channelKey; }
    public void setChannelKey(String channelKey) { this.channelKey = channelKey; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getCreatedByLabel() { return createdByLabel; }
    public void setCreatedByLabel(String createdByLabel) { this.createdByLabel = createdByLabel; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(OffsetDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
}
