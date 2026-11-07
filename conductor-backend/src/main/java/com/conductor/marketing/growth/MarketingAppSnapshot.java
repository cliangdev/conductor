package com.conductor.marketing.growth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One public listing reading of an app for one ISO week ({@code capturedOn} is that week's Monday, UTC). Every
 * reading field is null when the public API did not return it. Written only by {@link GrowthSignalsJob}, through
 * {@link MarketingAppSnapshotRepository#insertIfAbsent}. See V195.
 */
@Entity
@Table(name = "marketing_app_snapshot")
public class MarketingAppSnapshot {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "app_id", length = 32, nullable = false)
    private String appId;

    @Column(name = "captured_on", nullable = false)
    private LocalDate capturedOn;

    /** The App Store storefront this reading came from. */
    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "price")
    private BigDecimal price;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "rating_avg")
    private BigDecimal ratingAvg;

    @Column(name = "rating_count")
    private Long ratingCount;

    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "title")
    private String title;

    @Column(name = "subtitle")
    private String subtitle;

    @Column(name = "description_hash", length = 64)
    private String descriptionHash;

    /** JSON object {@code {keyword: rank | null}}; null when no keyword was checked. */
    @Column(name = "keyword_ranks", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String keywordRanks;

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getAppId() { return appId; }
    public LocalDate getCapturedOn() { return capturedOn; }
    public String getCountry() { return country; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public BigDecimal getRatingAvg() { return ratingAvg; }
    public Long getRatingCount() { return ratingCount; }
    public String getVersion() { return version; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getDescriptionHash() { return descriptionHash; }
    public String getKeywordRanks() { return keywordRanks; }
}
