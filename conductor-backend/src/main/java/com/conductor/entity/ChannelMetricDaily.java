package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One day of one channel metric (optionally one dimension of it) on one connection. See V147. */
@Entity
@Table(name = "channel_metric_daily")
public class ChannelMetricDaily {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "connection_id", length = 36, nullable = false)
    private String connectionId;

    @Column(name = "connector_id", nullable = false)
    private String connectorId;

    @Column(name = "channel", nullable = false)
    private String channel;

    @Column(name = "metric", nullable = false)
    private String metric;

    @Column(name = "dimension_key", nullable = false)
    private String dimensionKey = "";

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "value", nullable = false)
    private BigDecimal value;

    @Column(name = "provisional", nullable = false)
    private boolean provisional;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @PrePersist
    void assign() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (fetchedAt == null) {
            fetchedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getDimensionKey() { return dimensionKey; }
    public void setDimensionKey(String dimensionKey) { this.dimensionKey = dimensionKey; }
    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public boolean isProvisional() { return provisional; }
    public void setProvisional(boolean provisional) { this.provisional = provisional; }
    public OffsetDateTime getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(OffsetDateTime fetchedAt) { this.fetchedAt = fetchedAt; }
}
