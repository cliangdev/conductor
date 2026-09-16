package com.conductor.repository;

import com.conductor.entity.ProjectSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectSettingsRepository extends JpaRepository<ProjectSettings, String> {

    Optional<ProjectSettings> findByProjectId(String projectId);

    /** Projects that have opted into the Knowledge Center — the population the "what works" weekly
     *  connector's feed provisioner scans, since a feed with nowhere to file its digest is pointless. */
    List<ProjectSettings> findAllByKnowledgeEnabledTrue();
}
