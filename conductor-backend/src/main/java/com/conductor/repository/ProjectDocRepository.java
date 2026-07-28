package com.conductor.repository;

import com.conductor.entity.ProjectDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectDocRepository extends JpaRepository<ProjectDoc, String> {

    // LEFT JOIN FETCH, not JOIN FETCH: an agent-authored doc has no createdBy/updatedBy user, and an
    // inner join would silently drop it from every listing.
    @Query("SELECT DISTINCT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.project.id = :projectId AND d.folder IS NULL")
    List<ProjectDoc> findByProjectIdAndFolderIsNull(@Param("projectId") String projectId);

    @Query("SELECT DISTINCT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.project.id = :projectId AND d.folder.id = :folderId")
    List<ProjectDoc> findByProjectIdAndFolderId(@Param("projectId") String projectId, @Param("folderId") String folderId);

    @Query("SELECT DISTINCT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.project.id = :projectId")
    List<ProjectDoc> findAllByProjectId(@Param("projectId") String projectId);

    boolean existsByProjectIdAndFolderIsNullAndTitle(String projectId, String title);

    boolean existsByProjectIdAndFolderIdAndTitle(String projectId, String folderId, String title);

    // ...AndIdNot variants: a relocate re-checks uniqueness for a doc that may not be moving, so the
    // doc must not collide with itself.
    boolean existsByProjectIdAndFolderIsNullAndTitleAndIdNot(String projectId, String title, String id);

    boolean existsByProjectIdAndFolderIdAndTitleAndIdNot(String projectId, String folderId, String title, String id);

    @Query("SELECT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.id = :docId")
    Optional<ProjectDoc> findByIdWithUsers(@Param("docId") String docId);

    @Query("SELECT d FROM ProjectDoc d WHERE d.project.id = :projectId AND (LOWER(d.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(d.content) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<ProjectDoc> searchByProjectIdAndQuery(@Param("projectId") String projectId, @Param("query") String query);
}
