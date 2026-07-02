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
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
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
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "CODE_REVIEW", null, "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue moved to Code review");
    }

    @Test
    void statusChangedDefaultsNounToWorkItem() {
        // No noun in metadata → defaults to the generic "Work Item".
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta(null, "DONE", "Done", "terminal"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Work Item moved to Done");
    }

    @Test
    void statusChangedWithAssigneeIncludesAssigneeInDescription() {
        Map<String, String> meta = statusChangedMeta("Issue", "IN_PROGRESS", "In Progress", "in_progress");
        meta.put("assigneeName", "Alice");
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, meta);

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue moved to In Progress");
        assertThat(result).contains("Assigned to Alice");
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains("Assigned to Alice — " + ISSUE_TITLE);
    }

    @Test
    void statusChangedWithoutAssigneeUsesIssueTitle() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
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
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, meta);

        String result = discordProvider.format(event);

        assertThat(result).contains("\"fields\"");
        assertThat(result).contains("Pull Request");
        assertThat(result).contains(prUrl);
    }

    @Test
    void statusChangedWithoutPrUrlDoesNotContainFields() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "CODE_REVIEW", "Code Review", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).doesNotContain("\"fields\"");
    }

    // --- WORK_ITEM_STATUS_CHANGED color is derived from the target status category (Workflow-agnostic) ---

    @Test
    void colorForTerminalCategoryIsGreen() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "DONE", "Done", "terminal"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x57F287); // green 5763719
    }

    @Test
    void colorForInProgressCategoryIsBlue() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "CODE_REVIEW", "Code Review", "in_progress"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x5865F2); // blue 5793266
    }

    @Test
    void colorForOpenCategoryIsGrey() {
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "DRAFT", "Draft", "open"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x99AAB5); // grey
    }

    @Test
    void colorForMissingCategoryIsDefaultBlue() {
        // No toCategory → the provider falls back to its default blue.
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                statusChangedMeta("Issue", "BACKLOG", "Backlog", null));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":" + 0x58B9FF); // default blue 5814783
    }

    // --- Non-status events (unchanged by the COND-18 collapse) ---

    @Test
    void formatReviewerAssignedContainsReviewerName() {
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEWER_ASSIGNED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "reviewerName", "Alice"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Reviewer Assigned");
        assertThat(result).contains("Alice");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedApprovedUsesGreenColorAndTitle() {
        NotificationEvent event = NotificationEvent.of(
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
        NotificationEvent event = NotificationEvent.of(
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
        NotificationEvent event = NotificationEvent.of(
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
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("Review Submitted");
        assertThat(result).contains("5814783");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedReviewerNameAppearsInDescription() {
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "APPROVED",
                        "reviewerName", "Dave"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Dave on: " + ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedNoReviewerNameFallsBackToIssueTitle() {
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "verdict", "APPROVED"));

        String result = discordProvider.format(event);

        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).doesNotContain(" on: " + ISSUE_TITLE);
    }

    @Test
    void formatCommentAddedContainsAuthor() {
        NotificationEvent event = NotificationEvent.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Added");
        assertThat(result).contains("Bob");
    }

    @Test
    void formatCommentReplyContainsAuthor() {
        NotificationEvent event = NotificationEvent.of(
                EventType.COMMENT_REPLY, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Carol"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Reply");
        assertThat(result).contains("Carol");
    }

    @Test
    void formatMemberJoinedContainsMemberName() {
        NotificationEvent event = NotificationEvent.of(
                EventType.MEMBER_JOINED, PROJECT_ID,
                Map.of("memberName", "Dave"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Member Joined");
        assertThat(result).contains("Dave");
    }

    @Test
    void formatMemberRoleChangedContainsRoleAndName() {
        NotificationEvent event = NotificationEvent.of(
                EventType.MEMBER_ROLE_CHANGED, PROJECT_ID,
                Map.of("memberName", "Eve", "role", "ADMIN"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Member Role Changed");
        assertThat(result).contains("Eve");
        assertThat(result).contains("ADMIN");
    }

    // --- Embed envelope / escaping / transport ---

    @Test
    void formatContainsValidEmbedStructure() {
        NotificationEvent event = NotificationEvent.of(
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
        NotificationEvent event = NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, meta);

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
        NotificationEvent event = NotificationEvent.of(
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
        NotificationEvent event = NotificationEvent.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Added");
        assertThat(result).contains("Bob commented on: " + ISSUE_TITLE);
        assertThat(result).doesNotContain("> ");
    }

    @Test
    void formatCommentReplyWithExcerptIncludesExcerptInDescription() {
        NotificationEvent event = NotificationEvent.of(
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
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":5814783"); // 0x58B9FF default blue
    }

    @Test
    void colorForCommentAddedIsDefaultBlue() {
        NotificationEvent event = NotificationEvent.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, "workItemTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("\"color\":5814783"); // 0x58B9FF default blue
    }
}
