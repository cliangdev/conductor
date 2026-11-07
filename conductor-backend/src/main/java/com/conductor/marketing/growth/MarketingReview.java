package com.conductor.marketing.growth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * One App Store review of a watched app, as the public RSS feed returned it. Written only by
 * {@link GrowthSignalsJob}, through {@link MarketingReviewRepository#insertIfAbsent}. See V195.
 */
@Entity
@Table(name = "marketing_review")
public class MarketingReview {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "app_id", length = 32, nullable = false)
    private String appId;

    @Column(name = "review_id", length = 64, nullable = false)
    private String reviewId;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "title")
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "author")
    private String author;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getAppId() { return appId; }
    public String getReviewId() { return reviewId; }
    public Integer getRating() { return rating; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getAuthor() { return author; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
}
