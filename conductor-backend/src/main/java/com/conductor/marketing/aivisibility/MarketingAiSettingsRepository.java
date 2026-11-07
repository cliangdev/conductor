package com.conductor.marketing.aivisibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketingAiSettingsRepository extends JpaRepository<MarketingAiSettings, String> {
}
