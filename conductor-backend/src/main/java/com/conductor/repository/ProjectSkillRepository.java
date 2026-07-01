package com.conductor.repository;

import com.conductor.entity.ProjectSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectSkillRepository extends JpaRepository<ProjectSkill, String> {

    List<ProjectSkill> findAllByProjectId(String projectId);

    Optional<ProjectSkill> findByProjectIdAndSkillId(String projectId, String skillId);

    boolean existsByProjectIdAndSkillId(String projectId, String skillId);
}
