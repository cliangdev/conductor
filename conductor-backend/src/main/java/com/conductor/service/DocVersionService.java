package com.conductor.service;

import com.conductor.entity.DocVersion;
import com.conductor.entity.ProjectDoc;
import com.conductor.repository.DocVersionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocVersionService {

    private final DocVersionRepository docVersionRepository;
    private final ProjectDocService projectDocService;

    public DocVersionService(DocVersionRepository docVersionRepository, ProjectDocService projectDocService) {
        this.docVersionRepository = docVersionRepository;
        this.projectDocService = projectDocService;
    }

    @Transactional(readOnly = true)
    public List<DocVersion> listVersions(String projectId, String docId) {
        projectDocService.getDoc(projectId, docId);
        return docVersionRepository.findByDocIdOrderByVersionNumberDesc(docId);
    }

    /**
     * Loads a version, asserting it belongs to {@code docId} in {@code projectId} — see
     * {@link ProjectDocService#getDoc} for why a mismatch reads as "not found".
     */
    @Transactional(readOnly = true)
    public DocVersion getVersion(String projectId, String docId, String versionId) {
        DocVersion version = docVersionRepository.findByIdWithAuthor(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Version not found: " + versionId));
        if (!version.getDoc().getId().equals(docId)) {
            throw new EntityNotFoundException("Version not found: " + versionId);
        }
        projectDocService.getDoc(projectId, docId);
        return version;
    }

    @Transactional
    public ProjectDoc restoreVersion(String projectId, String docId, String versionId, ProjectActor actor) {
        DocVersion version = getVersion(projectId, docId, versionId);
        return projectDocService.updateDoc(projectId, docId, version.getContent(), actor);
    }
}
