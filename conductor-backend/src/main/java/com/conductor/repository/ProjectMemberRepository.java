package com.conductor.repository;

import com.conductor.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @Query("SELECT pm FROM ProjectMember pm JOIN FETCH pm.user WHERE pm.project.id = :projectId AND pm.user.id IN :userIds")
    List<ProjectMember> findByProjectIdAndUserIdIn(@Param("projectId") String projectId,
                                                   @Param("userIds") java.util.Collection<String> userIds);

    /** A project's members with their users in one query (the members screen). */
    @Query("SELECT pm FROM ProjectMember pm JOIN FETCH pm.user WHERE pm.project.id = :projectId")
    List<ProjectMember> findByProjectIdWithUser(@Param("projectId") String projectId);

    @Query("SELECT pm.project.id, COUNT(pm) FROM ProjectMember pm WHERE pm.project.id IN :projectIds GROUP BY pm.project.id")
    List<Object[]> countGroupedByProjectIds(@Param("projectIds") java.util.Collection<String> projectIds);

    long countByProjectId(String projectId);

    boolean existsByProjectIdAndUserId(String projectId, String userId);

    /** As {@code InviteRepository#clearRoles}: stores a deliberate empty (read-only) role set after the insert. */
    @Modifying
    @Query(value = "UPDATE project_members SET roles = '{}' WHERE id = :id", nativeQuery = true)
    void clearRoles(@Param("id") String id);

    /** Members holding ADMIN (the last-admin guard). */
    @Query(value = "SELECT COUNT(*) FROM project_members WHERE project_id = :projectId AND 'ADMIN' = ANY(roles)",
            nativeQuery = true)
    long countAdmins(@Param("projectId") String projectId);

}
