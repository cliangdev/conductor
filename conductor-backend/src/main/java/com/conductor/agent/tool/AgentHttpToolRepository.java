package com.conductor.agent.tool;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentHttpToolRepository extends JpaRepository<AgentHttpTool, String> {

    List<AgentHttpTool> findByProjectId(String projectId);

    Optional<AgentHttpTool> findByProjectIdAndSlug(String projectId, String slug);
}
