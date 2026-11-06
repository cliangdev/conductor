package com.conductor.marketing.budget;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketingBudgetRepository extends JpaRepository<MarketingBudget, String> {

    Optional<MarketingBudget> findByProjectIdAndMonth(String projectId, String month);
}
