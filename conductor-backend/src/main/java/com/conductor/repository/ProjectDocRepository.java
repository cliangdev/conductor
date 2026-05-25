package com.conductor.repository;

import com.conductor.entity.ProjectDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectDocRepository extends JpaRepository<ProjectDoc, String> {

    List<ProjectDoc> findByProjectIdAndFolderIsNull(String projectId);

    List<ProjectDoc> findByProjectIdAndFolderId(String projectId, String folderId);

    boolean existsByProjectIdAndFolderIsNullAndTitle(String projectId, String title);

    boolean existsByProjectIdAndFolderIdAndTitle(String projectId, String folderId, String title);

    @Query("SELECT d FROM ProjectDoc d WHERE d.project.id = :projectId AND (LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(d.content) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<ProjectDoc> searchByProjectIdAndQuery(@Param("projectId") String projectId, @Param("query") String query);
}
