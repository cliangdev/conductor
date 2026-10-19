package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry in the append-only record of posting-consent events (V153): who consented, withdrew or was
 * refused, on which Post, through which door, and what they were shown. Rows are only ever inserted.
 * {@code publish_consent} stays the current-state row; this is the history it cannot keep.
 */
@Entity
@Table(name = "publish_consent_event")
public class PublishConsentEvent {

    public static final String CONSENTED = "CONSENTED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String REFUSED_MACHINE_CREDENTIAL = "REFUSED_MACHINE_CREDENTIAL";
    public static final String REFUSED_STALE = "REFUSED_STALE";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, updatable = false)
    private String projectId;

    @Column(name = "work_item_id", length = 36, nullable = false, updatable = false)
    private String workItemId;

    /** Null when the actor had no user — a project key or run token. */
    @Column(name = "user_id", length = 36, updatable = false)
    private String userId;

    @Column(name = "action", length = 40, nullable = false, updatable = false)
    private String action;

    @Column(name = "subject_hash", length = 64, updatable = false)
    private String subjectHash;

    @Column(name = "declaration_json", columnDefinition = "TEXT", updatable = false)
    private String declarationJson;

    @Column(name = "declaration_version", length = 64, updatable = false)
    private String declarationVersion;

    @Column(name = "channel", length = 20, updatable = false)
    private String channel;

    @Column(name = "request_id", length = 36, updatable = false)
    private String requestId;

    @Column(name = "credential_type", length = 30, updatable = false)
    private String credentialType;

    @Column(name = "ip", length = 64, updatable = false)
    private String ip;

    @Column(name = "user_agent", length = 512, updatable = false)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getSubjectHash() { return subjectHash; }
    public void setSubjectHash(String subjectHash) { this.subjectHash = subjectHash; }
    public String getDeclarationJson() { return declarationJson; }
    public void setDeclarationJson(String declarationJson) { this.declarationJson = declarationJson; }
    public String getDeclarationVersion() { return declarationVersion; }
    public void setDeclarationVersion(String declarationVersion) { this.declarationVersion = declarationVersion; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getCredentialType() { return credentialType; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
