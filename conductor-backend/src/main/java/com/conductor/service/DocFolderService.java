package com.conductor.service;

import com.conductor.entity.DocFolder;
import com.conductor.entity.Project;
import com.conductor.exception.BusinessException;
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

    /**
     * Renames and/or reparents a folder as one operation — the folder tree's counterpart to
     * {@link ProjectDocService#relocate}, and atomic for the same reasons.
     *
     * @param newName        new name, or null to keep the current one
     * @param targetParentId destination parent, or null for the top level
     * @param moveRequested  whether {@code targetParentId} is meaningful; see
     *                       {@link ProjectDocService#relocate} for why the caller has to say so
     */
    @Transactional
    public DocFolder relocateFolder(String projectId, String folderId, String newName,
                                    String targetParentId, boolean moveRequested) {
        DocFolder folder = getFolder(projectId, folderId);

        String name = newName != null ? newName : folder.getName();
        String currentParentId = folder.getParent() != null ? folder.getParent().getId() : null;
        String destinationParentId = moveRequested ? targetParentId : currentParentId;

        DocFolder destination = null;
        if (destinationParentId != null) {
            destination = getFolder(projectId, destinationParentId);
            assertNotOwnDescendant(folder, destination);
        }

        boolean nameConflict = destinationParentId == null
                ? docFolderRepository.existsByProjectIdAndParentIsNullAndNameAndIdNot(projectId, name, folderId)
                : docFolderRepository.existsByProjectIdAndParentIdAndNameAndIdNot(projectId, destinationParentId, name, folderId);

        if (nameConflict) {
            throw new ConflictException("A folder named '" + name + "' already exists in the target location");
        }

        folder.setName(name);
        folder.setParent(destination);
        return docFolderRepository.save(folder);
    }

    /**
     * Refuses to move a folder inside itself or one of its own descendants, which would detach that
     * whole subtree from the tree into an unreachable cycle.
     */
    private void assertNotOwnDescendant(DocFolder folder, DocFolder destination) {
        for (DocFolder ancestor = destination; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getId().equals(folder.getId())) {
                throw new BusinessException(
                        "Cannot move folder '" + folder.getName() + "' into itself or one of its subfolders");
            }
        }
    }

    /**
     * Deletes a folder and its subfolders. Documents inside are not deleted: {@code folder_id} is
     * {@code ON DELETE SET NULL} (V46), so they resurface at the project root.
     */
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
