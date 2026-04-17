package com.conductor.repository;

import com.conductor.entity.Project;
import com.conductor.entity.ProjectVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    @Query("SELECT p FROM Project p WHERE EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = p AND pm.user.id = :userId)")
    List<Project> findProjectsByMemberUserId(@Param("userId") String userId);

    @Query("SELECT p FROM Project p WHERE p.orgId = :orgId AND p.visibility = :visibility")
    List<Project> findByOrgIdAndVisibility(@Param("orgId") String orgId, @Param("visibility") ProjectVisibility visibility);

    @Query("SELECT p FROM Project p WHERE p.teamId = :teamId AND p.visibility = :visibility")
    List<Project> findByTeamIdAndVisibility(@Param("teamId") String teamId, @Param("visibility") ProjectVisibility visibility);

    boolean existsByKey(String key);
}
