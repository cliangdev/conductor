package com.conductor.marketing.switchback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A weekly on/off test of one Apple Ads keyword. The plan ({@code weeks}, {@code decisionRule}, {@code minN}) is fixed
 * once the test is RUNNING; {@code result}, {@code verdict} and {@code changeWorkItemId} are written once, when it
 * settles. The JSON columns are held as text and converted by {@link SwitchbackTestService}. See V193.
 */
@Entity
@Table(name = "marketing_switchback_test")
public class MarketingSwitchbackTest {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false, updatable = false)
    private String projectId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Column(name = "keyword_id", length = 64, nullable = false)
    private String keywordId;

    @Column(name = "keyword_text", length = 200, nullable = false)
    private String keywordText;

    @Column(name = "weeks", nullable = false, columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String weeks;

    @Column(name = "decision_rule", nullable = false, columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String decisionRule;

    @Column(name = "min_n", nullable = false)
    private int minN;

    @Column(name = "status", length = 16, nullable = false)
    private String status = SwitchbackTestService.DRAFT;

    @Column(name = "verdict", length = 32)
    private String verdict;

    @Column(name = "result", columnDefinition = "JSONB")
    @ColumnTransformer(write = "?::jsonb")
    private String result;

    @Column(name = "change_work_item_id", length = 36)
    private String changeWorkItemId;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "settled_at")
    private OffsetDateTime settledAt;

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
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeywordId() { return keywordId; }
    public void setKeywordId(String keywordId) { this.keywordId = keywordId; }
    public String getKeywordText() { return keywordText; }
    public void setKeywordText(String keywordText) { this.keywordText = keywordText; }
    public String getWeeks() { return weeks; }
    public void setWeeks(String weeks) { this.weeks = weeks; }
    public String getDecisionRule() { return decisionRule; }
    public void setDecisionRule(String decisionRule) { this.decisionRule = decisionRule; }
    public int getMinN() { return minN; }
    public void setMinN(int minN) { this.minN = minN; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getChangeWorkItemId() { return changeWorkItemId; }
    public void setChangeWorkItemId(String changeWorkItemId) { this.changeWorkItemId = changeWorkItemId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(OffsetDateTime settledAt) { this.settledAt = settledAt; }
}
