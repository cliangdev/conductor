package com.conductor.repository;

import com.conductor.entity.DocFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocFolderRepository extends JpaRepository<DocFolder, String> {

    List<DocFolder> findByProjectIdAndParentIsNull(String projectId);

    List<DocFolder> findByProjectIdAndParentId(String projectId, String parentId);

    boolean existsByProjectIdAndParentIsNullAndName(String projectId, String name);

    boolean existsByProjectIdAndParentIdAndName(String projectId, String parentId, String name);
}
