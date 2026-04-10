package com.conductor.repository;

import com.conductor.entity.ProjectApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectApiKeyRepository extends JpaRepository<ProjectApiKey, String> {

    Optional<ProjectApiKey> findByKeyHash(String keyHash);

    List<ProjectApiKey> findByProjectIdAndRevokedAtIsNull(String projectId);
}
