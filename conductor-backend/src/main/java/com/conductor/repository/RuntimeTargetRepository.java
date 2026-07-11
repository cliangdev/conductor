package com.conductor.repository;

import com.conductor.entity.RuntimeTarget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RuntimeTargetRepository extends JpaRepository<RuntimeTarget, String> {
    List<RuntimeTarget> findByProjectId(String projectId);
    Optional<RuntimeTarget> findByProjectIdAndName(String projectId, String name);
    boolean existsByProjectIdAndName(String projectId, String name);
}
