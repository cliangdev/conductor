package com.conductor.agent.run;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findByAgentId(String agentId);

    List<AgentRun> findByProjectId(String projectId);
}
