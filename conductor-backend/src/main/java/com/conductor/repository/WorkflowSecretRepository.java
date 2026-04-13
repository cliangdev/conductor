package com.conductor.repository;

import com.conductor.entity.WorkflowSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowSecretRepository extends JpaRepository<WorkflowSecret, String> {

    List<WorkflowSecret> findByProjectId(String projectId);

    Optional<WorkflowSecret> findByProjectIdAndKey(String projectId, String key);

    long countByProjectId(String projectId);

    @Transactional
    void deleteByProjectIdAndKey(String projectId, String key);
}
