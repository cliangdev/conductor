package com.conductor.repository;

import com.conductor.entity.ProjectRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepositoryRepository extends JpaRepository<ProjectRepository, String> {

    List<ProjectRepository> findByProjectId(String projectId);
}
