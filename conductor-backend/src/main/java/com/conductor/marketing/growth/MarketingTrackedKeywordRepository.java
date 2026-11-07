package com.conductor.marketing.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MarketingTrackedKeywordRepository extends JpaRepository<MarketingTrackedKeyword, String> {

    List<MarketingTrackedKeyword> findByProjectIdOrderByKeywordAscCountryAsc(String projectId);

    /**
     * Removes the project's whole list at once. A bulk statement, so it runs before the replacement rows are
     * inserted (a derived delete would be flushed after them and trip the unique key on a repeated keyword).
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MarketingTrackedKeyword k where k.projectId = :projectId")
    int deleteAllForProject(@Param("projectId") String projectId);
}
