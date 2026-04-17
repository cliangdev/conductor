package com.conductor.repository;

import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMember, String> {

    List<ProjectMember> findByProjectId(String projectId);

    List<ProjectMember> findByUserId(String userId);

    Optional<ProjectMember> findByProjectIdAndUserId(String projectId, String userId);

    boolean existsByProjectIdAndUserId(String projectId, String userId);

    @Query("SELECT COUNT(pm) FROM ProjectMember pm WHERE pm.project.id = :projectId AND pm.role = :role")
    long countByProjectIdAndRole(@Param("projectId") String projectId, @Param("role") MemberRole role);

    List<ProjectMember> findByUserIdAndRole(String userId, MemberRole role);
}
