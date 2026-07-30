package com.conductor.service.view;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything a Work Item produced, assembled inside the service transaction (see
 * {@link com.conductor.service.WorkItemSnapshotService}) -- its own fields plus the documents, comments,
 * assets, and reviews it accumulated. Read model, not a persisted shape: exists to feed a downstream
 * consumer (today, {@code KnowledgeSignalSink}'s terminal-status ingestion) a complete picture of a Work
 * Item without that consumer needing to know about five separate JPA repositories.
 */
public record WorkItemSnapshot(
        String workItemId,
        String projectId,
        String projectKey,
        Integer sequenceNumber,
        String title,
        String description,
        String type,
        String workflow,
        String currentStatus,
        String assigneeName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<Doc> documents,
        List<Note> comments,
        List<Artifact> assets,
        List<Verdict> reviews) {

    /**
     * Whether this Work Item produced something -- a terminal item with none of these is noise, not
     * knowledge. A non-blank {@code description} counts on its own (a work item can be "done" purely by
     * virtue of what it says, with no document/comment/asset/review ever attached), but a
     * whitespace-only description does not.
     */
    public boolean hasArtifacts() {
        return !documents.isEmpty()
                || !comments.isEmpty()
                || !assets.isEmpty()
                || !reviews.isEmpty()
                || (description != null && !description.isBlank());
    }

    /** e.g. {@code "ENG-42"} from the project key and sequence number; {@code null} when either is missing. */
    public String key() {
        if (projectKey == null || projectKey.isBlank() || sequenceNumber == null) {
            return null;
        }
        return projectKey + "-" + sequenceNumber;
    }

    /** A Document: intent captured in a filename + content. {@code truncated} is set once content is
     *  cut down to the ingestion content cap (see {@code WorkItemSnapshotService}). */
    public record Doc(String filename, String contentType, String content, boolean truncated,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** A Comment, with its author's display name and (if any) the filename of the document it was left
     *  on, already resolved so a consumer never needs to touch the lazy associations itself. */
    public record Note(String id, String authorName, String documentFilename, String content,
                        OffsetDateTime createdAt) {
    }

    /** An Asset: what was actually built/done, as opposed to a Document's intent. */
    public record Artifact(String type, String label, String kind, String ref, boolean done,
                            OffsetDateTime createdAt) {
    }

    /** A Review verdict, with the reviewer's display name already resolved (see
     *  {@code WorkItemSnapshotService} -- {@code Review.reviewerId} is a raw column, not an association). */
    public record Verdict(String reviewerName, String verdict, String body, OffsetDateTime submittedAt) {
    }
}
