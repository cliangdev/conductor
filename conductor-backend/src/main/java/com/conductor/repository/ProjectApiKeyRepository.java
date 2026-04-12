package com.conductor.repository;

import com.conductor.entity.ProjectApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectApiKeyRepository extends JpaRepository<ProjectApiKey, String> {

    @Query("SELECT k FROM ProjectApiKey k JOIN FETCH k.project WHERE k.keyValue = :keyValue")
    Optional<ProjectApiKey> findByKeyValueWithProject(@Param("keyValue") String keyValue);

    List<ProjectApiKey> findByProjectIdAndRevokedAtIsNull(String projectId);
}
