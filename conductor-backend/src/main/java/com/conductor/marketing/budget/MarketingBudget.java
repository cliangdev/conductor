package com.conductor.marketing.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The paid-marketing cap of one workspace for one calendar month (UTC). See V191. */
@Entity
@Table(name = "marketing_budget")
public class MarketingBudget {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    /** {@code YYYY-MM}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "month", length = 7, nullable = false)
    private String month;

    @Column(name = "cap_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal capAmount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "USD";

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

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
    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
    public BigDecimal getCapAmount() { return capAmount; }
    public void setCapAmount(BigDecimal capAmount) { this.capAmount = capAmount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
