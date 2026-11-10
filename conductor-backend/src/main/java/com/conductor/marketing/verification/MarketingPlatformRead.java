package com.conductor.marketing.verification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One direct read of a platform's own UI or API for a metric over an explicit range of days. Never deleted:
 * the entity is immutable and its repository has no generic update or delete, so the history behind every verdict
 * stays as it was recorded. The one change ever made is a one-way retraction, done by a single narrow update
 * query in the repository (the entity itself stays immutable; re-read it to see the retraction). {@code conductorValue}, {@code backendValue} and {@code verdict} are what was true
 * at the moment of recording; the live ledger recomputes them on read. See V188.
 */
@Entity
@Immutable
@Table(name = "marketing_platform_read")
public class MarketingPlatformRead implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "channel", length = 16, nullable = false, updatable = false)
    private String channel;

    @Column(name = "metric", length = 64, nullable = false, updatable = false)
    private String metric;

    @Column(name = "day_from", nullable = false, updatable = false)
    private LocalDate dayFrom;

    @Column(name = "day_to", nullable = false, updatable = false)
    private LocalDate dayTo;

    @Column(name = "value", nullable = false, updatable = false)
    private BigDecimal value;

    @Column(name = "source_name", length = 120, nullable = false, updatable = false)
    private String sourceName;

    @Column(name = "note", length = 500, updatable = false)
    private String note;

    @Column(name = "recorded_by", length = 36, updatable = false)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "conductor_value", updatable = false)
    private BigDecimal conductorValue;

    @Column(name = "backend_value", updatable = false)
    private BigDecimal backendValue;

    @Column(name = "verdict", length = 16, updatable = false)
    private String verdict;

    @Column(name = "retracted_at", updatable = false)
    private OffsetDateTime retractedAt;

    @Column(name = "retracted_by", length = 36, updatable = false)
    private String retractedBy;

    @Column(name = "retraction_reason", length = 500, updatable = false)
    private String retractionReason;

    @Transient
    private boolean fresh = true;

    protected MarketingPlatformRead() {
    }

    public MarketingPlatformRead(String id, String projectId, String channel, String metric, LocalDate dayFrom,
                                 LocalDate dayTo, BigDecimal value, String sourceName, String note,
                                 String recordedBy, OffsetDateTime recordedAt, BigDecimal conductorValue,
                                 BigDecimal backendValue, String verdict) {
        this.id = id;
        this.projectId = projectId;
        this.channel = channel;
        this.metric = metric;
        this.dayFrom = dayFrom;
        this.dayTo = dayTo;
        this.value = value;
        this.sourceName = sourceName;
        this.note = note;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.conductorValue = conductorValue;
        this.backendValue = backendValue;
        this.verdict = verdict;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        this.fresh = false;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getChannel() {
        return channel;
    }

    public String getMetric() {
        return metric;
    }

    public LocalDate getDayFrom() {
        return dayFrom;
    }

    public LocalDate getDayTo() {
        return dayTo;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getNote() {
        return note;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    public BigDecimal getConductorValue() {
        return conductorValue;
    }

    public BigDecimal getBackendValue() {
        return backendValue;
    }

    public String getVerdict() {
        return verdict;
    }

    public OffsetDateTime getRetractedAt() {
        return retractedAt;
    }

    public String getRetractedBy() {
        return retractedBy;
    }

    public String getRetractionReason() {
        return retractionReason;
    }
}
