package com.conductor.knowledge.page;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown by {@code KnowledgePageService#batchWrite} when one or more writes in the batch lost a
 * concurrency race (stale {@code baseVersion}, or a create/delete targeting a path whose live state
 * doesn't match what the caller expected). Nothing in the batch is written when this is thrown --
 * the caller re-reads the conflicting paths and retries.
 */
public class KnowledgeConflictException extends RuntimeException {

    private final List<Conflict> conflicts;

    public KnowledgeConflictException(List<Conflict> conflicts) {
        super("Conflicting knowledge page writes: "
                + conflicts.stream().map(Conflict::path).collect(Collectors.joining(", ")));
        this.conflicts = conflicts;
    }

    /**
     * Same {@code conflicts} extension, caller-supplied message -- for a caller whose batch has a
     * narrower, more predictable failure mode than "one or more of N writes conflicted" (e.g. {@code
     * KnowledgePageService#dismissPage}'s two-write batch, where the realistic case is always the
     * client's own stale page version) and wants the 409 detail to read accordingly.
     */
    public KnowledgeConflictException(String message, List<Conflict> conflicts) {
        super(message);
        this.conflicts = conflicts;
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }

    /**
     * {@code currentVersion} is 0 and {@code currentContent} is null when the path has no live page at
     * all (a create/update raced with a delete, or the caller's {@code baseVersion} refers to a page
     * that no longer exists) -- there is no content to show in that case.
     */
    public record Conflict(String path, int currentVersion, String currentContent) {
    }
}
