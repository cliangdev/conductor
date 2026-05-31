package com.conductor.service;

import com.conductor.entity.DocFolder;
import com.conductor.entity.DocVersion;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.repository.DocFolderRepository;
import com.conductor.repository.DocVersionRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectDocService {

    private final ProjectDocRepository projectDocRepository;
    private final DocVersionRepository docVersionRepository;
    private final DocFolderRepository docFolderRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;

    @Lazy
    @Autowired
    private DocCommentService docCommentService;

    public ProjectDocService(
            ProjectDocRepository projectDocRepository,
            DocVersionRepository docVersionRepository,
            DocFolderRepository docFolderRepository,
            UserRepository userRepository,
            ProjectRepository projectRepository) {
        this.projectDocRepository = projectDocRepository;
        this.docVersionRepository = docVersionRepository;
        this.docFolderRepository = docFolderRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<ProjectDoc> getDocs(String projectId, String folderId) {
        if (folderId == null) {
            return projectDocRepository.findByProjectIdAndFolderIsNull(projectId);
        }
        return projectDocRepository.findByProjectIdAndFolderId(projectId, folderId);
    }

    @Transactional
    public ProjectDoc createDoc(String projectId, String folderId, String title, String content, String userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        DocFolder folder = null;
        boolean titleConflict;
        if (folderId == null) {
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIsNullAndTitle(projectId, title);
        } else {
            folder = docFolderRepository.findById(folderId)
                    .orElseThrow(() -> new EntityNotFoundException("Folder not found"));
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIdAndTitle(projectId, folderId, title);
        }

        if (titleConflict) {
            throw new ConflictException("A document titled '" + title + "' already exists in this location");
        }

        ProjectDoc doc = new ProjectDoc();
        doc.setProject(project);
        doc.setFolder(folder);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setCreatedBy(user);
        doc.setUpdatedBy(user);

        projectDocRepository.save(doc);

        DocVersion version = new DocVersion();
        version.setDoc(doc);
        version.setVersionNumber(1);
        version.setContent(content);
        version.setAuthor(user);
        docVersionRepository.save(version);

        return doc;
    }

    @Transactional(readOnly = true)
    public ProjectDoc getDoc(String docId) {
        return projectDocRepository.findByIdWithUsers(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found: " + docId));
    }

    @Transactional
    public ProjectDoc updateDoc(String docId, String content, String userId) {
        ProjectDoc doc = getDoc(docId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        doc.setContent(content);
        doc.setUpdatedBy(user);
        projectDocRepository.save(doc);

        int nextVersion = docVersionRepository.findTopByDocIdOrderByVersionNumberDesc(docId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);

        DocVersion version = new DocVersion();
        version.setDoc(doc);
        version.setVersionNumber(nextVersion);
        version.setContent(content);
        version.setAuthor(user);
        docVersionRepository.save(version);

        docCommentService.markCommentsStale(docId);

        return doc;
    }

    @Transactional
    public ProjectDoc renameDoc(String docId, String title) {
        ProjectDoc doc = getDoc(docId);

        String projectId = doc.getProject().getId();
        DocFolder folder = doc.getFolder();

        boolean titleConflict;
        if (folder == null) {
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIsNullAndTitle(projectId, title);
        } else {
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIdAndTitle(projectId, folder.getId(), title);
        }

        if (titleConflict) {
            throw new ConflictException("A document titled '" + title + "' already exists in this location");
        }

        doc.setTitle(title);
        return projectDocRepository.save(doc);
    }

    @Transactional
    public ProjectDoc moveDoc(String docId, String targetFolderId) {
        ProjectDoc doc = getDoc(docId);

        String projectId = doc.getProject().getId();

        DocFolder targetFolder = null;
        boolean titleConflict;
        if (targetFolderId == null) {
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIsNullAndTitle(projectId, doc.getTitle());
        } else {
            targetFolder = docFolderRepository.findById(targetFolderId)
                    .orElseThrow(() -> new EntityNotFoundException("Target folder not found"));
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIdAndTitle(projectId, targetFolderId, doc.getTitle());
        }

        if (titleConflict) {
            throw new ConflictException("A document titled '" + doc.getTitle() + "' already exists in the target location");
        }

        doc.setFolder(targetFolder);
        return projectDocRepository.save(doc);
    }

    @Transactional
    public void deleteDoc(String docId) {
        ProjectDoc doc = getDoc(docId);
        projectDocRepository.delete(doc);
    }

    @Transactional(readOnly = true)
    public List<ProjectDoc> searchDocs(String projectId, String query) {
        return projectDocRepository.searchByProjectIdAndQuery(projectId, query);
    }
}
