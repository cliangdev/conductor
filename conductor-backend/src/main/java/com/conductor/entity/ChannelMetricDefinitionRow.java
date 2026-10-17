package com.conductor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A metric a product metrics feed declares for one connection. See V150. */
@Entity
@Table(name = "channel_metric_definition")
public class ChannelMetricDefinitionRow {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "connection_id", length = 36, nullable = false)
    private String connectionId;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "metric", nullable = false)
    private String metric;

    @Column(name = "label", nullable = false)
    private String label;

    /** COUNT, CURRENCY or PERCENT. */
    @Column(name = "unit", nullable = false)
    private String unit;

    /** SUM, LEVEL or RATIO. */
    @Column(name = "aggregation", nullable = false)
    private String aggregation;

    @Column(name = "numerator")
    private String numerator;

    @Column(name = "denominator")
    private String denominator;

    /** UP or DOWN. */
    @Column(name = "good", nullable = false)
    private String good;

    @Column(name = "funnel_order")
    private Integer funnelOrder;

    @Column(name = "target_value")
    private BigDecimal targetValue;

    @Column(name = "target_direction")
    private String targetDirection;

    @Column(name = "target_window")
    private String targetWindow;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getAggregation() { return aggregation; }
    public void setAggregation(String aggregation) { this.aggregation = aggregation; }
    public String getNumerator() { return numerator; }
    public void setNumerator(String numerator) { this.numerator = numerator; }
    public String getDenominator() { return denominator; }
    public void setDenominator(String denominator) { this.denominator = denominator; }
    public String getGood() { return good; }
    public void setGood(String good) { this.good = good; }
    public Integer getFunnelOrder() { return funnelOrder; }
    public void setFunnelOrder(Integer funnelOrder) { this.funnelOrder = funnelOrder; }
    public BigDecimal getTargetValue() { return targetValue; }
    public void setTargetValue(BigDecimal targetValue) { this.targetValue = targetValue; }
    public String getTargetDirection() { return targetDirection; }
    public void setTargetDirection(String targetDirection) { this.targetDirection = targetDirection; }
    public String getTargetWindow() { return targetWindow; }
    public void setTargetWindow(String targetWindow) { this.targetWindow = targetWindow; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
