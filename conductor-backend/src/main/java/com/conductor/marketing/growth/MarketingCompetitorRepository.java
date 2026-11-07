package com.conductor.marketing.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingCompetitorRepository extends JpaRepository<MarketingCompetitor, String> {

    List<MarketingCompetitor> findByProjectIdAndActiveTrueOrderByNameAscAppIdAsc(String projectId);

    Optional<MarketingCompetitor> findByProjectIdAndAppId(String projectId, String appId);

    /** Projects that watch at least one competitor. */
    @Query("select distinct c.projectId from MarketingCompetitor c where c.active = true")
    List<String> findProjectIdsWithActiveCompetitors();
}
