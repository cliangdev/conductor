package com.conductor.marketing.alerts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarketingAlertStateRepository extends JpaRepository<MarketingAlertState, String> {

    List<MarketingAlertState> findByProjectId(String projectId);
}
