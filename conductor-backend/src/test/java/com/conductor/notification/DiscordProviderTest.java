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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Test
    void formatIssueSubmittedContainsCorrectTitleAndDescription() {
        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("embeds");
        assertThat(result).contains("Issue Submitted for Review");
        assertThat(result).contains(ISSUE_TITLE);
        assertThat(result).contains(PROJECT_ID);
        assertThat(result).contains(ISSUE_ID);
        assertThat(result).contains("5814783");
        assertThat(result).contains("timestamp");
    }

    @Test
    void formatReviewerAssignedContainsReviewerName() {
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEWER_ASSIGNED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE, "reviewerName", "Alice"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Reviewer Assigned");
        assertThat(result).contains("Alice");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatReviewSubmittedContainsVerdict() {
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE, "verdict", "APPROVED"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Review Submitted");
        assertThat(result).contains("APPROVED");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatIssueApprovedContainsTitle() {
        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_APPROVED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue Approved");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatIssueCompletedContainsTitle() {
        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_COMPLETED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE));

        String result = discordProvider.format(event);

        assertThat(result).contains("Issue Completed");
        assertThat(result).contains(ISSUE_TITLE);
    }

    @Test
    void formatCommentAddedContainsAuthor() {
        NotificationEvent event = NotificationEvent.of(
                EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE, "commentAuthor", "Bob"));

        String result = discordProvider.format(event);

        assertThat(result).contains("Comment Added");
        assertThat(result).contains("Bob");
    }

    @Test
    void formatCommentReplyContainsAuthor() {
        NotificationEvent event = NotificationEvent.of(
                EventType.COMMENT_REPLY, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE, "commentAuthor", "Carol"));

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

    @Test
    void formatContainsValidEmbedStructure() {
        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", ISSUE_TITLE));

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
        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", "Title with \"quotes\" and\nnewline"));

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
}
