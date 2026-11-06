package com.conductor.marketing.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingStopRuleRepository extends JpaRepository<MarketingStopRule, String> {

    List<MarketingStopRule> findByProjectIdAndActiveTrueOrderByCreatedAtAsc(String projectId);

    Optional<MarketingStopRule> findByIdAndProjectId(String id, String projectId);
}
