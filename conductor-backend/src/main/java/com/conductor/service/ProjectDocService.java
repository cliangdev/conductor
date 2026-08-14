package com.conductor.service;

import com.conductor.entity.DocFolder;
import com.conductor.entity.DocVersion;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.DocVersionRepository;
import com.conductor.repository.ProjectDocRepository;
import com.conductor.repository.ProjectRepository;
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
    private final DocFolderService docFolderService;
    private final ProjectRepository projectRepository;

    @Lazy
    @Autowired
    private DocCommentService docCommentService;

    public ProjectDocService(
            ProjectDocRepository projectDocRepository,
            DocVersionRepository docVersionRepository,
            DocFolderService docFolderService,
            ProjectRepository projectRepository) {
        this.projectDocRepository = projectDocRepository;
        this.docVersionRepository = docVersionRepository;
        this.docFolderService = docFolderService;
        this.projectRepository = projectRepository;
    }

    /**
     * Documents in one folder, or every document in the project when {@code recursive}. Recursive
     * exists so a client building the whole tree makes one call rather than one per folder.
     */
    @Transactional(readOnly = true)
    public List<ProjectDoc> getDocs(String projectId, String folderId, boolean recursive) {
        if (recursive) {
            return projectDocRepository.findAllByProjectId(projectId);
        }
        if (folderId == null) {
            return projectDocRepository.findByProjectIdAndFolderIsNull(projectId);
        }
        // Resolve the folder first so one from another project reads as "not found" rather than
        // silently returning an empty list.
        docFolderService.getFolder(projectId, folderId);
        return projectDocRepository.findByProjectIdAndFolderId(projectId, folderId);
    }

    @Transactional
    public ProjectDoc createDoc(String projectId, String folderId, String title, String content, ProjectActor actor) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        DocFolder folder = null;
        boolean titleConflict;
        if (folderId == null) {
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIsNullAndTitle(projectId, title);
        } else {
            folder = docFolderService.getFolder(projectId, folderId);
            titleConflict = projectDocRepository.existsByProjectIdAndFolderIdAndTitle(projectId, folderId, title);
        }

        if (titleConflict) {
            throw new ConflictException("A document titled '" + title + "' already exists in this location");
        }

        String stored = DocImageMarkers.normalize(content);

        ProjectDoc doc = new ProjectDoc();
        doc.setProject(project);
        doc.setFolder(folder);
        doc.setTitle(title);
        doc.setContent(stored);
        doc.setCreatedBy(actor.user());
        doc.setCreatedByLabel(actor.label());
        applyEditor(doc, actor);

        projectDocRepository.save(doc);

        DocVersion version = new DocVersion();
        version.setDoc(doc);
        version.setVersionNumber(1);
        version.setContent(stored);
        version.setAuthor(actor.user());
        version.setAuthorLabel(actor.label());
        docVersionRepository.save(version);

        return doc;
    }

    /**
     * Loads a doc, asserting it belongs to {@code projectId}. Every path that reaches a doc by id goes
     * through here: without the project assertion a credential scoped to one project could reach
     * another project's docs simply by putting its own project id in the URL. A mismatch reads as 404
     * rather than 403 so ids can't be enumerated.
     */
    @Transactional(readOnly = true)
    public ProjectDoc getDoc(String projectId, String docId) {
        ProjectDoc doc = projectDocRepository.findByIdWithUsers(docId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found: " + docId));
        if (!doc.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Document not found: " + docId);
        }
        return doc;
    }

    @Transactional
    public ProjectDoc updateDoc(String projectId, String docId, String content, ProjectActor actor) {
        ProjectDoc doc = getDoc(projectId, docId);

        // Signed image URLs never reach the database: a client that read this doc got freshly signed
        // URLs back, and writing those through verbatim would store links that expire in minutes.
        String stored = DocImageMarkers.normalize(content);

        doc.setContent(stored);
        applyEditor(doc, actor);
        projectDocRepository.save(doc);

        int nextVersion = docVersionRepository.findTopByDocIdOrderByVersionNumberDesc(docId)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);

        DocVersion version = new DocVersion();
        version.setDoc(doc);
        version.setVersionNumber(nextVersion);
        version.setContent(stored);
        version.setAuthor(actor.user());
        version.setAuthorLabel(actor.label());
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
    public ProjectDoc setTaskState(String projectId, String docId, int lineNumber, boolean checked, ProjectActor actor) {
        ProjectDoc doc = getDoc(projectId, docId);

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
        applyEditor(doc, actor);
        projectDocRepository.save(doc);

        return doc;
    }

    /**
     * Renames and/or moves a document as one operation.
     *
     * <p>Deliberately a single transactional method rather than a rename call followed by a move call.
     * Sequenced separately, the rename checks its new title against the <em>source</em> folder — which
     * is not where the document is going, so it can reject a title that is free at the destination —
     * and a conflict on the second call leaves the document renamed but unmoved.
     *
     * @param newTitle      new title, or null to keep the current one
     * @param targetFolderId destination folder, or null for the project root
     * @param moveRequested  whether {@code targetFolderId} is meaningful; false leaves the document
     *                       where it is. The caller decides this, because "no folder given" and "move
     *                       to root" are the same payload once the generated models drop the
     *                       distinction between an absent and a null field.
     */
    @Transactional
    public ProjectDoc relocate(String projectId, String docId, String newTitle,
                               String targetFolderId, boolean moveRequested) {
        ProjectDoc doc = getDoc(projectId, docId);

        String title = newTitle != null ? newTitle : doc.getTitle();
        String currentFolderId = doc.getFolder() != null ? doc.getFolder().getId() : null;
        String destinationFolderId = moveRequested ? targetFolderId : currentFolderId;

        DocFolder destination = destinationFolderId == null
                ? null
                : docFolderService.getFolder(projectId, destinationFolderId);

        // Checked against the destination, and excluding this doc so a no-op rename doesn't collide
        // with itself.
        boolean titleConflict = destinationFolderId == null
                ? projectDocRepository.existsByProjectIdAndFolderIsNullAndTitleAndIdNot(projectId, title, docId)
                : projectDocRepository.existsByProjectIdAndFolderIdAndTitleAndIdNot(projectId, destinationFolderId, title, docId);

        if (titleConflict) {
            throw new ConflictException(
                    "A document titled '" + title + "' already exists in the target location");
        }

        doc.setTitle(title);
        doc.setFolder(destination);
        return projectDocRepository.save(doc);
    }

    @Transactional
    public void deleteDoc(String projectId, String docId) {
        ProjectDoc doc = getDoc(projectId, docId);
        projectDocRepository.delete(doc);
    }

    /** @param limit caps the ranked result set at the query level -- callers pass whatever they intend
     *                to actually use (the REST endpoint's own page size, or an agent tool's already-
     *                clamped {@code limit} argument) rather than fetching everything and truncating after. */
    @Transactional(readOnly = true)
    public List<ProjectDoc> searchDocs(String projectId, String query, int limit) {
        return projectDocRepository.searchByProjectIdAndQuery(projectId, query, limit);
    }

    /** Records who last touched the doc — a user, or a machine actor's label. */
    private void applyEditor(ProjectDoc doc, ProjectActor actor) {
        doc.setUpdatedBy(actor.user());
        doc.setUpdatedByLabel(actor.label());
    }
}
