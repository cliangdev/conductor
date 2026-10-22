package com.conductor.marketing.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketingBenchmarkRepository extends JpaRepository<MarketingBenchmark, String> {

    List<MarketingBenchmark> findByProjectIdOrderByMetricRefAsc(String projectId);

    Optional<MarketingBenchmark> findByProjectIdAndMetricRef(String projectId, String metricRef);

    long deleteByProjectIdAndMetricRef(String projectId, String metricRef);
}
