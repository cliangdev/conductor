package com.conductor.notification;

public enum EventType {
    /**
     * A PRD has been submitted for review.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}
     * <p>Optional metadata keys: {@code actorName}
     */
    ISSUE_SUBMITTED("PRD submitted for review"),

    /**
     * A PRD has been approved.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}
     * <p>Optional metadata keys: {@code actorName}
     */
    ISSUE_APPROVED("PRD approved"),

    /**
     * An issue has been moved to the In Progress status.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}
     * <p>Optional metadata keys: {@code assigneeName}
     */
    ISSUE_IN_PROGRESS("Issue moved to In Progress"),

    /**
     * An issue has been moved to the Code Review status.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}
     * <p>Optional metadata keys: {@code prUrl}
     */
    ISSUE_IN_CODE_REVIEW("Issue moved to Code Review"),

    /**
     * An issue has been marked as Done.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}
     */
    ISSUE_COMPLETED("Issue marked as Done"),

    /**
     * An issue's status has changed from one value to another.
     *
     * <p>Required metadata keys: {@code issueId}, {@code issueTitle}, {@code fromStatus}, {@code toStatus}
     */
    ISSUE_STATUS_CHANGED("Issue status changed"),

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
    MEMBER_ROLE_CHANGED("Member role changed");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
