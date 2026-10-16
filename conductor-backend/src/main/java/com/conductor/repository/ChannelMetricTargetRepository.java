package com.conductor.repository;

import com.conductor.entity.ChannelMetricTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelMetricTargetRepository extends JpaRepository<ChannelMetricTarget, String> {

    List<ChannelMetricTarget> findByProjectId(String projectId);

    Optional<ChannelMetricTarget> findByProjectIdAndChannelAndMetric(String projectId, String channel, String metric);
}
