package com.conductor.marketing.alerts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** What one alert key last said for one workspace. See V192. */
@Entity
@Table(name = "marketing_alert_state")
public class MarketingAlertState {

    public static final String ACTIVE = "ACTIVE";
    public static final String RESOLVED = "RESOLVED";

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "alert_key", length = 200, nullable = false)
    private String alertKey;

    /** {@link #ACTIVE} or {@link #RESOLVED}. */
    @Column(name = "state", length = 16, nullable = false)
    private String state;

    @Column(name = "fingerprint", length = 200, nullable = false)
    private String fingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_posted_at")
    private OffsetDateTime lastPostedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @PrePersist
    void assign() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (firstSeenAt == null) {
            firstSeenAt = OffsetDateTime.now();
        }
    }

    public boolean isActive() {
        return ACTIVE.equals(state);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getAlertKey() { return alertKey; }
    public void setAlertKey(String alertKey) { this.alertKey = alertKey; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public OffsetDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(OffsetDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public OffsetDateTime getLastPostedAt() { return lastPostedAt; }
    public void setLastPostedAt(OffsetDateTime lastPostedAt) { this.lastPostedAt = lastPostedAt; }
    public OffsetDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(OffsetDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
