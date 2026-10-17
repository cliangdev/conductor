package com.conductor.repository;

import com.conductor.entity.ChannelMetricDefinitionRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChannelMetricDefinitionRepository extends JpaRepository<ChannelMetricDefinitionRow, String> {

    List<ChannelMetricDefinitionRow> findByProjectId(String projectId);

    List<ChannelMetricDefinitionRow> findByConnectionId(String connectionId);
}
