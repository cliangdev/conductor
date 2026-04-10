package com.conductor.repository;

import com.conductor.entity.ProjectSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProjectSettingsRepository extends JpaRepository<ProjectSettings, String> {

    Optional<ProjectSettings> findByProjectId(String projectId);
}
