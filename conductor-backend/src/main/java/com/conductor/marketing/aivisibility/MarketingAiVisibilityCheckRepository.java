package com.conductor.marketing.aivisibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface MarketingAiVisibilityCheckRepository extends JpaRepository<MarketingAiVisibilityCheck, String> {

    /** Every reading of one ISO week; its size is what the weekly call cap has already used. */
    List<MarketingAiVisibilityCheck> findByProjectIdAndWeekStart(String projectId, LocalDate weekStart);

    /** The readings from {@code since} on, oldest week first. */
    List<MarketingAiVisibilityCheck> findByProjectIdAndWeekStartGreaterThanEqualOrderByWeekStartAscAssistantAsc(
            String projectId, LocalDate since);

    /** The newest week each (question, assistant) pair was read, as {@code [questionId, assistant, weekStart]}. */
    @Query("select c.questionId, c.assistant, max(c.weekStart) from MarketingAiVisibilityCheck c "
            + "where c.projectId = :projectId group by c.questionId, c.assistant")
    List<Object[]> findLatestWeeks(@Param("projectId") String projectId);

    /**
     * Writes one reading unless (question, assistant, week) already has one, so a run repeated in the same week
     * leaves a single row. Returns 1 when written, 0 when the week was already read.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO marketing_ai_visibility_check
                (id, project_id, question_id, assistant, model, week_start, named, rank_in_answer, excerpt, checked_at)
            VALUES (:id, :projectId, :questionId, :assistant, :model, :weekStart, :named, :rankInAnswer, :excerpt,
                    :checkedAt)
            ON CONFLICT (question_id, assistant, week_start) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("projectId") String projectId,
                       @Param("questionId") String questionId,
                       @Param("assistant") String assistant,
                       @Param("model") String model,
                       @Param("weekStart") LocalDate weekStart,
                       @Param("named") boolean named,
                       @Param("rankInAnswer") Integer rankInAnswer,
                       @Param("excerpt") String excerpt,
                       @Param("checkedAt") OffsetDateTime checkedAt);
}
