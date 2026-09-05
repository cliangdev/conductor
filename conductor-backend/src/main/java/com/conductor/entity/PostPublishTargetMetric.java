package com.conductor.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One performance snapshot of one published destination, as its platform reported it during one pull
 * period. Written by {@code PostMetricsFeedPuller}; read by the Post's metrics view and the project's
 * top-posts query. See {@code V139__post_publish_target_metric.sql} for why this is its own table.
 */
@Entity
@Table(name = "post_publish_target_metric")
public class PostPublishTargetMetric {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "target_id", length = 36, nullable = false)
    private String targetId;

    @Column(name = "work_item_id", length = 36, nullable = false)
    private String workItemId;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "platform", length = 32, nullable = false)
    private String platform;

    @Column(name = "period_key", length = 32, nullable = false)
    private String periodKey;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    @Column(name = "views")
    private Long views;

    @Column(name = "likes")
    private Long likes;

    @Column(name = "comments")
    private Long comments;

    @Column(name = "shares")
    private Long shares;

    @Column(name = "saves")
    private Long saves;

    @Column(name = "reach")
    private Long reach;

    @Column(name = "impressions")
    private Long impressions;

    @Column(name = "watch_time_seconds")
    private Long watchTimeSeconds;

    @Column(name = "unavailable", nullable = false)
    private boolean unavailable;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra", columnDefinition = "jsonb")
    private JsonNode extra;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (observedAt == null) {
            observedAt = OffsetDateTime.now();
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public OffsetDateTime getObservedAt() { return observedAt; }
    public void setObservedAt(OffsetDateTime observedAt) { this.observedAt = observedAt; }
    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }
    public Long getLikes() { return likes; }
    public void setLikes(Long likes) { this.likes = likes; }
    public Long getComments() { return comments; }
    public void setComments(Long comments) { this.comments = comments; }
    public Long getShares() { return shares; }
    public void setShares(Long shares) { this.shares = shares; }
    public Long getSaves() { return saves; }
    public void setSaves(Long saves) { this.saves = saves; }
    public Long getReach() { return reach; }
    public void setReach(Long reach) { this.reach = reach; }
    public Long getImpressions() { return impressions; }
    public void setImpressions(Long impressions) { this.impressions = impressions; }
    public Long getWatchTimeSeconds() { return watchTimeSeconds; }
    public void setWatchTimeSeconds(Long watchTimeSeconds) { this.watchTimeSeconds = watchTimeSeconds; }
    public boolean isUnavailable() { return unavailable; }
    public void setUnavailable(boolean unavailable) { this.unavailable = unavailable; }
    public JsonNode getExtra() { return extra; }
    public void setExtra(JsonNode extra) { this.extra = extra; }
}
