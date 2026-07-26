package com.conductor.repository;

import com.conductor.entity.DocFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocFolderRepository extends JpaRepository<DocFolder, String> {

    List<DocFolder> findByProjectIdOrderByNameAsc(String projectId);

    boolean existsByProjectIdAndParentIsNullAndName(String projectId, String name);

    boolean existsByProjectIdAndParentIdAndName(String projectId, String parentId, String name);
}
