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
    public List<DocVersion> listVersions(String docId) {
        return docVersionRepository.findByDocIdOrderByVersionNumberDesc(docId);
    }

    @Transactional(readOnly = true)
    public DocVersion getVersion(String versionId) {
        return docVersionRepository.findById(versionId)
                .orElseThrow(() -> new EntityNotFoundException("Version not found: " + versionId));
    }

    @Transactional
    public ProjectDoc restoreVersion(String docId, String versionId, String userId) {
        DocVersion version = getVersion(versionId);
        return projectDocService.updateDoc(docId, version.getContent(), userId);
    }
}
