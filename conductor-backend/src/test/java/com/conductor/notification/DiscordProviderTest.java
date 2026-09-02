package com.conductor.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordProviderTest {

    @Mock
    private RestTemplate restTemplate;

    private DiscordProvider discordProvider;

    private static final String FRONTEND_URL = "http://localhost:3000";
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123/token";
    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";
    private static final String ISSUE_TITLE = "My Feature PRD";

    @BeforeEach
    void setUp() {
        discordProvider = new DiscordProvider(restTemplate, FRONTEND_URL);
    }

    /**
     * Build the enriched metadata WorkItemService now sends on a status change. Pass an explicit status label and
     * category to mimic a resolved {@code Statechart} status; assignee/prUrl are optional.
     */
    private Map<String, String> statusChangedMeta(String noun, String toStatus, String toStatusLabel,
                                                  String toCategory) {
        Map<String, String> meta = new HashMap<>();
        meta.put("workItemId", ISSUE_ID);
        meta.put("workItemTitle", ISSUE_TITLE);
        meta.put("projectId", PROJECT_ID);
        meta.put("workflow", "ENGINEERING");
        if (noun != null) meta.put("noun", noun);
        meta.put("fromStatus", "DRAFT");
        meta.put("toStatus", toStatus);
        if (toStatusLabel != null) meta.put("toStatusLabel", toStatusLabel);
        if (toCategory != null) meta.put("toCategory", toCategory);
        return meta;
    }

    // --- WORK_ITEM_STATUS_CHANGED: the single, Workflow-agnostic status event ---

    @Test
    void statusChangedTitleUsesNounAndToStatusLabel() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "IN_REVIEW", "In Review", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).contains("embeds");
        assertThat(result).contains("Issue moved to In Review");
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains(PROJECT_ID);
        assertThat(result).contains(ISSUE_ID);
        assertThat(result).contains("timestamp");
    }

    @Test
    void statusChangedHumanizesToStatusWhenNoLabel() {
        // No toStatusLabel in metadata → the provider humanizes the UPPER_SNAKE status id.
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "CODE_REVIEW", null, "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue moved to Code review");
    }

    @Test
    void statusChangedDefaultsNounToWorkItem() {
        // No noun in metadata → defaults to the generic "Work Item".
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta(null, "DONE", "Done", "terminal"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Work Item moved to Done");
    }

    @Test
    void statusChangedWithAssigneeIncludesAssigneeInDescription() {
        Map<String, String> meta = statusChangedMeta("Issue", "IN_PROGRESS", "In Progress", "in_progress");
        meta.put("assigneeName", "Alice");
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, meta);

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue moved to In Progress");
        assertThat(result).contains("Assigned to Alice");
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains("Assigned to Alice — " + ISSUE_TITLE);
    }

    @Test
    void statusChangedWithoutAssigneeUsesIssueTitle() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "IN_PROGRESS", "In Progress", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue moved to In Progress");
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).doesNotContain("Assigned to");
    }

    @Test
    void statusChangedWithPrUrlContainsFieldsWithPrLink() {
        String prUrl = "https://github.com/org/repo/pull/42";
        Map<String, String> meta = statusChangedMeta("Issue", "DONE", "Done", "terminal");
        meta.put("prUrl", prUrl);
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, meta);

        String result = discordProvider.format(event);

        assertThat(result).contains("\"fields\"");
        assertThat(result).contains("Pull Request");
        assertThat(result).contains(prUrl);
    }

    @Test
    void statusChangedWithoutPrUrlDoesNotContainFields() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "CODE_REVIEW", "Code Review", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).doesNotContain("\"fields\"");
    }

    // --- WORK_ITEM_STATUS_CHANGED color is derived from the target status category (Workflow-agnostic) ---

    @Test
    void colorForTerminalCategoryIsGreen() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "DONE", "Done", "terminal"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x57F287); // green 5763719
    }

    @Test
    void colorForInProgressCategoryIsBlue() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "CODE_REVIEW", "Code Review", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x5865F2); // blue 5793266
    }

    @Test
    void colorForOpenCategoryIsGrey() {
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "DRAFT", "Draft", "open"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x99AAB5); // grey
    }

    @Test
    void colorForMissingCategoryIsDefaultBlue() {
        // No toCategory → the provider falls back to its default blue.
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "BACKLOG", "Backlog", null));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x58B9FF); // default blue 5814783
    }

    // --- Non-status events (unchanged by the COND-18 collapse) ---

    @Test
    void formatReviewerAssignedContainsReviewerName() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEWER_ASSIGNED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "reviewerName", "Alice"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Reviewer Assigned");
        assertThat(result).contains("Alice");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedApprovedUsesGreenColorAndTitle() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "APPROVED",
                        "reviewerName", "Alice"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Review Approved");
        assertThat(result).contains(String.valueOf(0x57F287));
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains("Alice");
    }

    @Test
    void formatReviewSubmittedChangesRequestedUsesRedColorAndTitle() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "CHANGES_REQUESTED",
                        "reviewerName", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Changes Requested");
        assertThat(result).contains(String.valueOf(0xED4245));
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains("Bob");
    }

    @Test
    void formatReviewSubmittedCommentedUsesYellowColorAndTitle() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "COMMENTED",
                        "reviewerName", "Carol"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Review");
        assertThat(result).contains(String.valueOf(0xFEE75C));
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains("Carol");
    }

    @Test
    void formatReviewSubmittedUnknownVerdictUsesDefaultBlueAndTitle() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("Review Submitted");
        assertThat(result).contains("5814783");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedReviewerNameAppearsInDescription() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "APPROVED",
                        "reviewerName", "Dave"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Dave on: " + ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedNoReviewerNameFallsBackToIssueTitle() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "APPROVED"));

        String result = discordProvider.format(event);

        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).doesNotContain(" on: " + ISSUE_TITLE);
    }

    @Test
    void formatCommentAddedContainsAuthor() {
        NotificationMessage event = NotificationMessage.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Added");
        assertThat(result).contains("Bob");
    }

    @Test
    void formatCommentReplyContainsAuthor() {
        NotificationMessage event = NotificationMessage.of(
                EventType.COMMENT_REPLY, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Carol"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Reply");
        assertThat(result).contains("Carol");
    }

    @Test
    void formatMemberJoinedContainsMemberName() {
        NotificationMessage event = NotificationMessage.of(
                EventType.MEMBER_JOINED, PROJECT_ID,
                Map.of("memberName", "Dave"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Member Joined");
        assertThat(result).contains("Dave");
    }

    @Test
    void formatMemberRoleChangedContainsRoleAndName() {
        NotificationMessage event = NotificationMessage.of(
                EventType.MEMBER_ROLE_CHANGED, PROJECT_ID,
                Map.of("memberName", "Eve", "role", "ADMIN"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Member Role Changed");
        assertThat(result).contains("Eve");
        assertThat(result).contains("ADMIN");
    }

    // --- WORKFLOW_RUN_FAILED ---

    @Test
    void formatWorkflowRunFailedContainsWorkflowNameStepAndErrorReason() {
        NotificationMessage event = NotificationMessage.of(
                EventType.WORKFLOW_RUN_FAILED, PROJECT_ID,
                Map.of("runId", "run-1", "workflowId", "wf-1", "workflowName", "Nightly Sync",
                        "stepId", "push_image", "errorReason", "CLAUDE_TIMEOUT",
                        "summary", "The step exceeded its timeout_minutes.",
                        "remediation", "Increase timeout_minutes.",
                        "runUrl", "http://localhost:3000/app/projects/proj-1/workflows/wf-1/runs/run-1"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Nightly Sync run failed");
        assertThat(result).contains("push_image");
        assertThat(result).contains("CLAUDE_TIMEOUT");
        assertThat(result).contains("The step exceeded its timeout_minutes.");
        assertThat(result).contains("Increase timeout_minutes.");
        assertThat(result).contains("http://localhost:3000/app/projects/proj-1/workflows/wf-1/runs/run-1");
    }

    @Test
    void formatWorkflowRunFailedUsesRedColor() {
        NotificationMessage event = NotificationMessage.of(
                EventType.WORKFLOW_RUN_FAILED, PROJECT_ID,
                Map.of("runId", "run-1", "workflowId", "wf-1", "workflowName", "Nightly Sync"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0xED4245);
    }

    @Test
    void formatWorkflowRunFailedWithoutAResolvableStepUsesGenericFallbackMessage() {
        // The 24h stuck-run sweep and the zero-jobs-enqueued path have no single failing step to
        // point at -- the embed must still render sensibly rather than an empty description.
        NotificationMessage event = NotificationMessage.of(
                EventType.WORKFLOW_RUN_FAILED, PROJECT_ID,
                Map.of("runId", "run-1", "workflowId", "wf-1", "workflowName", "Nightly Sync"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Nightly Sync run failed");
        assertThat(result).contains("The run did not complete successfully.");
    }

    @Test
    void formatWorkflowRunFailedLinksToTheRunUrlFromMetadata_notTheIssuesLink() {
        NotificationMessage event = NotificationMessage.of(
                EventType.WORKFLOW_RUN_FAILED, PROJECT_ID,
                Map.of("runId", "run-1", "workflowId", "wf-1", "workflowName", "Nightly Sync",
                        "runUrl", "http://localhost:3000/app/projects/proj-1/workflows/wf-1/runs/run-1"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"url\":\"http://localhost:3000/app/projects/proj-1/workflows/wf-1/runs/run-1\"");
        assertThat(result).doesNotContain("/issues/");
    }

    // --- Embed envelope / escaping / transport ---

    @Test
    void formatContainsValidEmbedStructure() {
        NotificationMessage event = NotificationMessage.of(
                EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "IN_REVIEW", "In Review", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).startsWith("{\"embeds\":[{");
        assertThat(result).contains("\"title\":");
        assertThat(result).contains("\"description\":");
        assertThat(result).contains("\"url\":");
        assertThat(result).contains("\"color\":");
        assertThat(result).contains("\"timestamp\":");
    }

    @Test
    void formatEscapesSpecialCharacters() {
        Map<String, String> meta = statusChangedMeta("Issue", "IN_REVIEW", "In Review", "in_progress");
        meta.put("workItemTitle", "Title with \"quotes\" and\nnewline");
        NotificationMessage event = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, meta);

        String result = discordProvider.format(event);

        assertThat(result).contains("\\\"quotes\\\"");
        assertThat(result).contains("\\n");
    }

    @Test
    void sendCallsRestTemplateWithCorrectPayload() {
        when(restTemplate.postForEntity(eq(WEBHOOK_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.noContent().build());

        String payload = "{\"embeds\":[{\"title\":\"Test\"}]}";
        discordProvider.send(WEBHOOK_URL, payload);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(eq(WEBHOOK_URL), entityCaptor.capture(), eq(String.class));

        String body = entityCaptor.getValue().getBody().toString();
        assertThat(body).isEqualTo(payload);
        assertThat(entityCaptor.getValue().getHeaders().getContentType().toString())
                .contains("application/json");
    }

    @Test
    void formatCommentAddedWithExcerptIncludesExcerptInDescription() {
        NotificationMessage event = NotificationMessage.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE,
                        "commentAuthor", "Bob", "excerpt", "This is a selected excerpt"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Added");
        assertThat(result).contains("Bob");
        assertThat(result).contains("> This is a selected excerpt");
    }

    @Test
    void formatCommentAddedWithoutExcerptRendersWithoutError() {
        NotificationMessage event = NotificationMessage.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Added");
        assertThat(result).contains("Bob commented on: " + ISSUE_TITLE);
        assertThat(result).doesNotContain("> ");
    }

    @Test
    void formatCommentReplyWithExcerptIncludesExcerptInDescription() {
        NotificationMessage event = NotificationMessage.of(
                EventType.COMMENT_REPLY, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE,
                        "commentAuthor", "Carol", "excerpt", "Quoted reply text"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Reply");
        assertThat(result).contains("Carol");
        assertThat(result).contains("> Quoted reply text");
    }

    @Test
    void sendDoesNotThrowOnRestClientException() {
        when(restTemplate.postForEntity(eq(WEBHOOK_URL), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request"));

        assertThatNoException().isThrownBy(() -> discordProvider.send(WEBHOOK_URL, "{\"embeds\":[]}"));
    }

    @Test
    void sendLogsWarnOnNon2xxResponse() {
        when(restTemplate.postForEntity(eq(WEBHOOK_URL), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.badRequest().body("error"));

        assertThatNoException().isThrownBy(() -> discordProvider.send(WEBHOOK_URL, "{\"embeds\":[]}"));
    }

    @Test
    void colorForReviewSubmittedWithUnknownVerdictIsDefaultBlue() {
        NotificationMessage event = NotificationMessage.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":5814783"); // 0x58B9FF default blue
    }

    @Test
    void colorForCommentAddedIsDefaultBlue() {
        NotificationMessage event = NotificationMessage.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":5814783"); // 0x58B9FF default blue
    }

    // ── The card has to be clickable ───────────────────────────────────────────────────────────

    @Test
    void aCardLinksToTheWorkflowScopedDetailRoute() {
        // The detail route is /{area}/{nouns}/{displayId}. The old /issues/{uuid} shape only ever resolved
        // for engineering, so a card about a Post pointed at a page that does not exist.
        String json = discordProvider.format(NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, "proj-1",
                Map.of("workItemId", "wi-1", "workItemTitle", "Launch teaser", "toStatus", "PUBLISHED",
                        "noun", "Post", "area", "MARKETING", "displayId", "CLT-1")));

        assertThat(json).contains("/app/projects/proj-1/marketing/posts/CLT-1");
        assertThat(json).doesNotContain("/issues/wi-1");
    }

    @Test
    void aCardFallsBackToTheLegacyLinkWhenTheEventCarriesNoRouting() {
        // An emitter that has not been enriched still produces a link rather than a broken half of one.
        String json = discordProvider.format(NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, "proj-1",
                Map.of("workItemId", "wi-1", "workItemTitle", "An issue", "toStatus", "DONE")));

        assertThat(json).contains("/app/projects/proj-1/issues/wi-1");
    }

    @Test
    void aManualPublishAlertSaysWhatToDoAndWhere() {
        String json = discordProvider.format(NotificationMessage.of(EventType.POST_AWAITING_MANUAL, "proj-1",
                Map.of("workItemId", "wi-1", "workItemTitle", "Launch teaser", "platform", "tiktok",
                        "accountLabel", "TikTok (manual)", "noun", "Post",
                        "area", "MARKETING", "displayId", "CLT-1")));

        assertThat(json).contains("due to be published by hand");
        assertThat(json).contains("Launch teaser");
        assertThat(json).contains("tiktok");
        assertThat(json).contains("/app/projects/proj-1/marketing/posts/CLT-1");
    }

}
