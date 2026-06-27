package com.conductor.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, String> {

    List<Agent> findByProjectId(String projectId);

    Optional<Agent> findByProjectIdAndSlug(String projectId, String slug);

    boolean existsByProjectIdAndSlug(String projectId, String slug);
}
