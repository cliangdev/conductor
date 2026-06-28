package com.conductor.repository;

import com.conductor.entity.Issue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IssueRepository extends JpaRepository<Issue, String> {

    List<Issue> findByProjectId(String projectId);

    List<Issue> findByProjectIdAndType(String projectId, String type);

    List<Issue> findByProjectIdAndCurrentStatus(String projectId, String currentStatus);

    List<Issue> findByProjectIdAndTypeAndCurrentStatus(String projectId, String type, String currentStatus);

    @Query("SELECT COALESCE(MAX(i.sequenceNumber), 0) FROM Issue i WHERE i.project.id = :projectId")
    Integer findMaxSequenceNumberByProjectId(@Param("projectId") String projectId);

    @Query("SELECT i FROM Issue i JOIN i.project p WHERE p.key = :projectKey AND i.sequenceNumber = :sequenceNumber")
    Optional<Issue> findByProjectKeyAndSequenceNumber(@Param("projectKey") String projectKey, @Param("sequenceNumber") Integer sequenceNumber);
}
