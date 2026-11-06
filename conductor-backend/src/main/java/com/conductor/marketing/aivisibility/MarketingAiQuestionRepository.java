package com.conductor.marketing.aivisibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarketingAiQuestionRepository extends JpaRepository<MarketingAiQuestion, String> {

    /** Every question of the project, active or not, oldest first. */
    List<MarketingAiQuestion> findByProjectIdOrderByCreatedAtAscIdAsc(String projectId);

    List<MarketingAiQuestion> findByProjectIdAndActiveTrueOrderByCreatedAtAscIdAsc(String projectId);

    /** Projects with at least one active question: the ones the weekly job checks. */
    @Query("select distinct q.projectId from MarketingAiQuestion q where q.active = true")
    List<String> findProjectIdsWithActiveQuestions();
}
