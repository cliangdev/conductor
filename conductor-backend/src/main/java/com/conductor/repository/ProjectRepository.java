package com.conductor.repository;

import com.conductor.entity.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, String> {

    @Query("SELECT p FROM Project p WHERE EXISTS (SELECT pm FROM ProjectMember pm WHERE pm.project = p AND pm.user.id = :userId)")
    List<Project> findProjectsByMemberUserId(@Param("userId") String userId);

    boolean existsByKey(String key);

    /**
     * The project row, locked for the rest of the transaction. Work Item creation takes this before reading
     * {@code MAX(sequence_number) + 1}: two creates in the same project that read the maximum concurrently
     * compute the same next number, and the second one dies on {@code uq_work_items_project_sequence} with a
     * 500. Serialising creates per project on the row nobody else contends for costs one lock and nothing
     * else; the result is ignored, the lock is the point.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :projectId")
    Optional<Project> lockForSequence(@Param("projectId") String projectId);
}
