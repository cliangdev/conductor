package com.conductor.notification;

public enum EventType {
    /**
     * A Work Item's status has changed from one value to another. This is the single, Workflow-agnostic
     * status event (the legacy per-status events were collapsed into it so any Workflow's statuses notify
     * uniformly). The notification provider formats it from the enriched metadata.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code fromStatus}, {@code toStatus}
     * <p>Optional metadata keys: {@code workflow}, {@code noun}, {@code toStatusLabel}, {@code toCategory},
     * {@code assigneeName}, {@code prUrl}
     */
    ISSUE_STATUS_CHANGED("Work Item status changed"),

    /**
     * A reviewer has been assigned to a PRD.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code reviewerId}, {@code reviewerName}
     */
    REVIEWER_ASSIGNED("Reviewer assigned to a PRD"),

    /**
     * A review verdict has been submitted on a PRD.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code reviewerName}, {@code verdict}
     */
    REVIEW_SUBMITTED("Review verdict submitted"),

    /**
     * A comment has been added to a PRD.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code commentAuthor}
     * <p>Optional metadata keys: {@code excerpt}
     */
    COMMENT_ADDED("Comment added to a PRD"),

    /**
     * A reply has been added to a comment on a PRD.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code commentAuthor}
     * <p>Optional metadata keys: {@code excerpt}
     */
    COMMENT_REPLY("Reply added to a comment"),

    /**
     * A new member has joined the project.
     *
     * <p>Required metadata keys: {@code memberName}
     */
    MEMBER_JOINED("New member joined the project"),

    /**
     * A project member's role has been changed.
     *
     * <p>Required metadata keys: {@code memberName}, {@code role}
     */
    MEMBER_ROLE_CHANGED("Member role changed"),

    /**
     * A produced-output Asset has been recorded on a Work Item (COND-18).
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code assetType}
     */
    ASSET_ADDED("Asset added to a Work Item");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
