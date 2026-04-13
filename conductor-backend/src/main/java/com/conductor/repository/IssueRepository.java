package com.conductor.repository;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IssueRepository extends JpaRepository<Issue, String> {

    List<Issue> findByProjectId(String projectId);

    List<Issue> findByProjectIdAndType(String projectId, IssueType type);

    List<Issue> findByProjectIdAndStatus(String projectId, IssueStatus status);

    List<Issue> findByProjectIdAndTypeAndStatus(String projectId, IssueType type, IssueStatus status);

    @Query("SELECT COALESCE(MAX(i.sequenceNumber), 0) FROM Issue i WHERE i.project.id = :projectId")
    Integer findMaxSequenceNumberByProjectId(@Param("projectId") String projectId);
}
