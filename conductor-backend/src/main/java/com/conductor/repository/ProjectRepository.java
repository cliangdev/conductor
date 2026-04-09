package com.conductor.repository;

import com.conductor.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    @Query("SELECT p FROM Project p WHERE EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = p AND pm.user.id = :userId)")
    List<Project> findProjectsByMemberUserId(@Param("userId") String userId);
}
