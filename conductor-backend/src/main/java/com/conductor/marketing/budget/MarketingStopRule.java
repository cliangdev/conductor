package com.conductor.marketing.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A stop rule for one paid channel: "{@code metric} {@code comparator} {@code threshold} over the last
 * {@code windowDays} settled days, once {@code minSample} taps back it". Conductor records and evaluates it;
 * the bid or budget change itself is a human's. See V191.
 */
@Entity
@Table(name = "marketing_stop_rule")
public class MarketingStopRule {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "channel", length = 32, nullable = false)
    private String channel;

    /** {@code channel}, {@code campaign:<id>} or {@code keyword:<id>}. */
    @Column(name = "scope", length = 200, nullable = false)
    private String scope = "channel";

    @Column(name = "metric", length = 64, nullable = false)
    private String metric;

    /** {@code GT} or {@code LT}. */
    @Column(name = "comparator", length = 2, nullable = false)
    private String comparator;

    @Column(name = "threshold", nullable = false)
    private BigDecimal threshold;

    @Column(name = "min_sample", nullable = false)
    private int minSample;

    @Column(name = "window_days", nullable = false)
    private int windowDays = 7;

    @Column(name = "note")
    private String note;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void assign() {
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
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getComparator() { return comparator; }
    public void setComparator(String comparator) { this.comparator = comparator; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
    public int getMinSample() { return minSample; }
    public void setMinSample(int minSample) { this.minSample = minSample; }
    public int getWindowDays() { return windowDays; }
    public void setWindowDays(int windowDays) { this.windowDays = windowDays; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
