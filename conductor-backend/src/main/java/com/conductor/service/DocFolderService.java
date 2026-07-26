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

    /**
     * Every folder in the project as a flat list — clients nest it themselves via {@code parentId}.
     * Returning only root folders (as this once did) made sub-folders uncreatable-but-invisible: the
     * tree UI already recurses over the full list, and path-addressed API clients need the whole tree
     * in one call.
     */
    @Transactional(readOnly = true)
    public List<DocFolder> getFolders(String projectId) {
        return docFolderRepository.findByProjectIdOrderByNameAsc(projectId);
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
            parent = getFolder(projectId, parentId);
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
    public DocFolder renameFolder(String projectId, String folderId, String newName) {
        DocFolder folder = getFolder(projectId, folderId);

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
    public void deleteFolder(String projectId, String folderId) {
        DocFolder folder = getFolder(projectId, folderId);
        docFolderRepository.delete(folder);
    }

    /**
     * Loads a folder, asserting it belongs to {@code projectId} — see
     * {@link ProjectDocService#getDoc} for why a mismatch reads as "not found" rather than 403.
     */
    @Transactional(readOnly = true)
    public DocFolder getFolder(String projectId, String folderId) {
        DocFolder folder = docFolderRepository.findById(folderId)
                .orElseThrow(() -> new EntityNotFoundException("Folder not found: " + folderId));
        if (!folder.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Folder not found: " + folderId);
        }
        return folder;
    }
}
