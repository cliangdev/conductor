package com.conductor.notification;

public enum EventType {
    ISSUE_SUBMITTED("PRD submitted for review"),
    ISSUE_APPROVED("PRD approved"),
    ISSUE_COMPLETED("Issue marked as completed"),
    REVIEWER_ASSIGNED("Reviewer assigned to a PRD"),
    REVIEW_SUBMITTED("Review verdict submitted"),
    COMMENT_ADDED("Comment added to a PRD"),
    COMMENT_REPLY("Reply added to a comment"),
    MEMBER_JOINED("New member joined the project"),
    MEMBER_ROLE_CHANGED("Member role changed"),
    ISSUE_STATUS_CHANGED("Issue status changed");

    private final String description;

    EventType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
