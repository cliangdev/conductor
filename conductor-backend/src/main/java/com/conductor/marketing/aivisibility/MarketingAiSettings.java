package com.conductor.marketing.aivisibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;

/**
 * The words that count as naming a workspace's brand, a JSON array of strings. A workspace with no row uses its own
 * project name. See V196.
 */
@Entity
@Table(name = "marketing_ai_settings")
public class MarketingAiSettings {

    @Id
    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "brand_terms", nullable = false, columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String brandTerms;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getBrandTerms() { return brandTerms; }
    public void setBrandTerms(String brandTerms) { this.brandTerms = brandTerms; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
