package com.conductor.marketing.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** An industry benchmark for one metric of a workspace, with where it came from. See V158. */
@Entity
@Table(name = "marketing_benchmark")
public class MarketingBenchmark {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    /** {@code CHANNEL.metric}, e.g. {@code STORE.conversion_rate}, or {@code PRODUCT.<key>}. */
    @Column(name = "metric_ref", length = 96, nullable = false)
    private String metricRef;

    @Column(name = "label")
    private String label;

    @Column(name = "value", nullable = false)
    private BigDecimal value;

    @Column(name = "range_text")
    private String rangeText;

    @Column(name = "segment")
    private String segment;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "checked_on", nullable = false)
    private LocalDate checkedOn;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void assign() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getMetricRef() { return metricRef; }
    public void setMetricRef(String metricRef) { this.metricRef = metricRef; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public String getRangeText() { return rangeText; }
    public void setRangeText(String rangeText) { this.rangeText = rangeText; }
    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public LocalDate getCheckedOn() { return checkedOn; }
    public void setCheckedOn(LocalDate checkedOn) { this.checkedOn = checkedOn; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
