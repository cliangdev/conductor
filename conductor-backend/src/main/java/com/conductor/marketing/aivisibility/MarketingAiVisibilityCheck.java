package com.conductor.marketing.aivisibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One reading: whether an assistant named the brand for a question in an ISO week ({@code weekStart} is that week's
 * Monday, UTC). Written only by {@link AiVisibilityService}, through
 * {@link MarketingAiVisibilityCheckRepository#insertIfAbsent}. An assistant that was skipped or failed has no row,
 * so a row always means the assistant answered. See V196.
 */
@Entity
@Table(name = "marketing_ai_visibility_check")
public class MarketingAiVisibilityCheck {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "question_id", length = 36, nullable = false)
    private String questionId;

    /** The model provider id: claude, openai, ... */
    @Column(name = "assistant", length = 32, nullable = false)
    private String assistant;

    @Column(name = "model", length = 120)
    private String model;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "named", nullable = false)
    private boolean named;

    /** 1-based position of the first list item naming the brand; null when not named or the answer is no list. */
    @Column(name = "rank_in_answer")
    private Integer rankInAnswer;

    @Column(name = "excerpt", length = 600)
    private String excerpt;

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getQuestionId() { return questionId; }
    public String getAssistant() { return assistant; }
    public String getModel() { return model; }
    public LocalDate getWeekStart() { return weekStart; }
    public boolean isNamed() { return named; }
    public Integer getRankInAnswer() { return rankInAnswer; }
    public String getExcerpt() { return excerpt; }
    public OffsetDateTime getCheckedAt() { return checkedAt; }
}
