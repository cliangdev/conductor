package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Last-successful fetch result for a pull connection; UPSERT keyed by connection_id. */
@Entity
@Table(name = "connection_data_cache")
public class ConnectionDataCache {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "connection_id", length = 36, nullable = false)
    private String connectionId;

    @Column(name = "data_json", columnDefinition = "JSONB", nullable = false)
    @ColumnTransformer(write = "?::jsonb")
    private String dataJson;

    @Column(name = "health_status", length = 32, nullable = false)
    private String healthStatus;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    /** Reason the last fetch attempt failed; null when the last attempt succeeded. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (healthStatus == null) {
            healthStatus = "HEALTHY";
        }
        if (fetchedAt == null) {
            fetchedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public OffsetDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(OffsetDateTime fetchedAt) { this.fetchedAt = fetchedAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
