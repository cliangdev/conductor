package com.conductor.service;

import com.conductor.entity.DocFolder;
import com.conductor.entity.DocVersion;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProjectDocService {

    /**
     * One GFM task list item. Tolerates leading indent (nested lists), blockquote prefixes, all four
     * marker forms ({@code - * +} and {@code 1.} / {@code 1)}), and either checked state. Group 1 is
     * everything up to and including the opening bracket; the state character follows it. Kept in sync
     * with {@code toggleTaskLine} in the frontend's {@code src/lib/task-list.ts}.
     */
    private static final Pattern TASK_LIST_ITEM =
            Pattern.compile("^(\\s*(?:>\\s*)*(?:[-*+]|\\d{1,9}[.)])\\s+\\[)[ xX]\\]");

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

    /**
     * Flips a single GFM task list item in place so a reader can tick a checkbox without entering the
     * editor.
     *
     * <p>Deliberately does <em>not</em> do the two things {@link #updateDoc} does: no {@link DocVersion}
     * is minted (otherwise every click would add a history entry) and comments are not marked stale.
     * Marking them stale would be wrong anyway — {@code [ ]} and {@code [x]} are the same width, so no
     * line moves and every existing line anchor still points where it did.
     *
     * <p>Taking a line number rather than the whole document also removes the lost-update race the
     * full-content PUT has: two people ticking different boxes no longer clobber each other.
     */
    @Transactional
    public ProjectDoc setTaskState(String docId, int lineNumber, boolean checked, String userId) {
        ProjectDoc doc = getDoc(docId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found"));

        String content = doc.getContent() == null ? "" : doc.getContent();
        // Two-arg split: the 1-arg form drops trailing empty strings, which would silently eat the
        // document's trailing newline on every single toggle.
        String[] lines = content.split("\n", -1);

        if (lineNumber < 1 || lineNumber > lines.length) {
            throw new BusinessException("Line " + lineNumber + " is out of range for this document");
        }

        String line = lines[lineNumber - 1];
        Matcher matcher = TASK_LIST_ITEM.matcher(line);
        if (!matcher.find()) {
            throw new ConflictException(
                    "Line " + lineNumber + " is no longer a task list item — the document has changed");
        }

        // group(1) ends just before the state character, so the remainder of the line (starting at the
        // closing bracket, and including any trailing \r on CRLF content) is everything after it.
        lines[lineNumber - 1] =
                matcher.group(1) + (checked ? "x" : " ") + line.substring(matcher.end(1) + 1);
        doc.setContent(String.join("\n", lines));
        doc.setUpdatedBy(user);
        projectDocRepository.save(doc);

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
