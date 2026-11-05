package com.conductor.repository;

import com.conductor.entity.Milestone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MilestoneRepository extends JpaRepository<Milestone, String> {

    /** A project's milestones in display order: explicit order first, then soonest target, then name. */
    List<Milestone> findAllByProjectIdOrderBySortOrderAscTargetOnAscNameAsc(String projectId);

    Optional<Milestone> findByIdAndProjectId(String id, String projectId);

    boolean existsByProjectIdAndNameIgnoreCase(String projectId, String name);

    Optional<Milestone> findByProjectIdAndNameIgnoreCase(String projectId, String name);
}
