package com.conductor.repository;

import com.conductor.entity.ProjectDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectDocRepository extends JpaRepository<ProjectDoc, String> {

    List<ProjectDoc> findByProjectIdAndFolderIsNull(String projectId);

    List<ProjectDoc> findByProjectIdAndFolderId(String projectId, String folderId);

    boolean existsByProjectIdAndFolderIsNullAndTitle(String projectId, String title);

    boolean existsByProjectIdAndFolderIdAndTitle(String projectId, String folderId, String title);
}
