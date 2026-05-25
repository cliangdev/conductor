package com.conductor.service;

import com.conductor.entity.DocFolder;
import com.conductor.entity.Project;
import com.conductor.exception.ConflictException;
import com.conductor.repository.DocFolderRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocFolderService {

    private final DocFolderRepository docFolderRepository;
    private final ProjectDocRepository projectDocRepository;
    private final ProjectRepository projectRepository;

    public DocFolderService(
            DocFolderRepository docFolderRepository,
            ProjectDocRepository projectDocRepository,
            ProjectRepository projectRepository) {
        this.docFolderRepository = docFolderRepository;
        this.projectDocRepository = projectDocRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<DocFolder> getRootFolders(String projectId) {
        return docFolderRepository.findByProjectIdAndParentIsNull(projectId);
    }

    @Transactional(readOnly = true)
    public List<DocFolder> getChildFolders(String projectId, String parentId) {
        return docFolderRepository.findByProjectIdAndParentId(projectId, parentId);
    }

    @Transactional
    public DocFolder createFolder(String projectId, String parentId, String name) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        boolean nameConflict;
        DocFolder parent = null;
        if (parentId == null) {
            nameConflict = docFolderRepository.existsByProjectIdAndParentIsNullAndName(projectId, name);
        } else {
            parent = getFolder(parentId);
            nameConflict = docFolderRepository.existsByProjectIdAndParentIdAndName(projectId, parentId, name);
        }

        if (nameConflict) {
            throw new ConflictException("A folder named '" + name + "' already exists in this location");
        }

        DocFolder folder = new DocFolder();
        folder.setProject(project);
        folder.setParent(parent);
        folder.setName(name);

        return docFolderRepository.save(folder);
    }

    @Transactional
    public DocFolder renameFolder(String folderId, String newName) {
        DocFolder folder = getFolder(folderId);

        String projectId = folder.getProject().getId();
        DocFolder parentFolder = folder.getParent();

        boolean nameConflict;
        if (parentFolder == null) {
            nameConflict = docFolderRepository.existsByProjectIdAndParentIsNullAndName(projectId, newName);
        } else {
            nameConflict = docFolderRepository.existsByProjectIdAndParentIdAndName(projectId, parentFolder.getId(), newName);
        }

        if (nameConflict) {
            throw new ConflictException("A folder named '" + newName + "' already exists in this location");
        }

        folder.setName(newName);
        return docFolderRepository.save(folder);
    }

    @Transactional
    public void deleteFolder(String folderId) {
        DocFolder folder = getFolder(folderId);
        docFolderRepository.delete(folder);
    }

    @Transactional(readOnly = true)
    public DocFolder getFolder(String folderId) {
        return docFolderRepository.findById(folderId)
                .orElseThrow(() -> new EntityNotFoundException("Folder not found: " + folderId));
    }
}
