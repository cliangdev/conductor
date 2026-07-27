package com.conductor.signal;

/**
 * String constants for {@link Signal#type()}. These are deliberately the same strings already
 * persisted in {@code workflow_runs.trigger_type}, so routing a signal through this bus requires
 * no data migration -- an existing trigger-type column value is a valid {@code Signal.type}
 * as-is.
 *
 * <p>{@code GITHUB_PULL_REQUEST_MERGED} is a flat, single-segment-suffix name -- NOT
 * {@code "github.pull_request.merged"} -- on purpose. A merged PR today deliberately does not
 * fire the same review-workflow path as an open/labeled/synchronized/reopened PR (see
 * {@code GitHubConnector.handleEvent}'s split between the two). If the merged type were a child
 * segment of {@code github.pull_request}, a subscriber that (incorrectly) prefix- or glob-matched
 * {@code "github.pull_request.*"} or {@code "github.pull_request**"} would silently start
 * receiving merge events too. Naming it as an unrelated flat string makes that class of mistake
 * structurally impossible: see {@link SignalGlobTest} for the pair this guards.
 */
public final class SignalTypes {

    public static final String CONDUCTOR_WORK_ITEM_STATUS_CHANGED = "conductor.work_item.status_changed";
    public static final String CONDUCTOR_WORK_ITEM_REVIEWER_ASSIGNED = "conductor.work_item.reviewer_assigned";
    public static final String CONDUCTOR_WORK_ITEM_REVIEW_SUBMITTED = "conductor.work_item.review_submitted";
    public static final String CONDUCTOR_WORK_ITEM_COMMENT_ADDED = "conductor.work_item.comment_added";
    public static final String CONDUCTOR_WORK_ITEM_COMMENT_REPLIED = "conductor.work_item.comment_replied";
    public static final String CONDUCTOR_WORK_ITEM_ASSET_ADDED = "conductor.work_item.asset_added";

    public static final String CONDUCTOR_WORKFLOW_AUTO_PAUSED = "conductor.workflow.auto_paused";

    public static final String CONDUCTOR_PROJECT_MEMBER_JOINED = "conductor.project.member_joined";
    public static final String CONDUCTOR_PROJECT_MEMBER_ROLE_CHANGED = "conductor.project.member_role_changed";

    public static final String GITHUB_PULL_REQUEST = "github.pull_request";
    public static final String GITHUB_PULL_REQUEST_MERGED = "github.pull_request_merged";

    private SignalTypes() {
    }
}
