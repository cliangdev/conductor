package com.conductor.marketing.switchback;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingSwitchbackTestRepository extends JpaRepository<MarketingSwitchbackTest, String> {

    List<MarketingSwitchbackTest> findByProjectIdOrderByCreatedAtDesc(String projectId);

    Optional<MarketingSwitchbackTest> findByIdAndProjectId(String id, String projectId);

    List<MarketingSwitchbackTest> findByStatus(String status);

    List<MarketingSwitchbackTest> findByProjectIdAndKeywordIdAndStatus(String projectId, String keywordId, String status);

    /** The row, locked until the transaction ends, so two settlers cannot both see it RUNNING. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MarketingSwitchbackTest t where t.id = :id")
    Optional<MarketingSwitchbackTest> lockById(@Param("id") String id);
}
