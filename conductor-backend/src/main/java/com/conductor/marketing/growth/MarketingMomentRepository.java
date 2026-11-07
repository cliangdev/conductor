package com.conductor.marketing.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingMomentRepository extends JpaRepository<MarketingMoment, String> {

    List<MarketingMoment> findByProjectIdOrderByMomentDateAscMomentKeyAsc(String projectId);

    Optional<MarketingMoment> findByProjectIdAndMomentKey(String projectId, String momentKey);
}
