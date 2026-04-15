package com.conductor.service;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.GitHubWebhookEventRepository;
import com.conductor.repository.IssueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookProcessorTest {

    @Mock
    private GitHubWebhookEventRepository webhookEventRepository;

    @Mock
    private IssueRepository issueRepository;

    @InjectMocks
    private GitHubWebhookProcessor processor;

    private static final String PROJECT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ISSUE_ID = "11111111-2222-3333-4444-555555555555";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        processor = new GitHubWebhookProcessor(webhookEventRepository, issueRepository, objectMapper);
    }

    private GitHubWebhookEvent buildEvent(String eventType, String payload) {
        GitHubWebhookEvent event = new GitHubWebhookEvent();
        event.setId("evt-1");
        event.setProjectId(PROJECT_ID);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(WebhookEventStatus.PENDING);
        event.setAttempts(0);
        return event;
    }

    private Issue buildIssue(IssueStatus status) {
        User creator = new User();
        creator.setId("user-1");

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Test Project");
        project.setKey("TEST");
        project.setCreatedBy(creator);
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());

        Issue issue = new Issue();
        issue.setId(ISSUE_ID);
        issue.setProject(project);
        issue.setType(IssueType.PRD);
        issue.setTitle("Test Issue");
        issue.setStatus(status);
        issue.setSequenceNumber(1);
        issue.setCreatedBy(creator);
        issue.setCreatedAt(OffsetDateTime.now());
        issue.setUpdatedAt(OffsetDateTime.now());
        return issue;
    }

    private String mergedPrPayload(String prBody, String prUrl) {
        return """
                {
                  "action": "closed",
                  "pull_request": {
                    "merged": true,
                    "body": "%s",
                    "html_url": "%s"
                  }
                }
                """.formatted(escapeJson(prBody), escapeJson(prUrl));
    }

    private String closedNotMergedPayload() {
        return """
                {
                  "action": "closed",
                  "pull_request": {
                    "merged": false,
                    "body": "Closes conductor/%s",
                    "html_url": "https://github.com/org/repo/pull/1"
                  }
                }
                """.formatted(ISSUE_ID);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    void mergedPrWithValidIssueIdTransitionsIssueToDone() {
        String prUrl = "https://github.com/org/repo/pull/42";
        String payload = mergedPrPayload("Closes conductor/" + ISSUE_ID, prUrl);
        GitHubWebhookEvent event = buildEvent("pull_request", payload);
        Issue issue = buildIssue(IssueStatus.IN_PROGRESS);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(issueRepository.save(any(Issue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        ArgumentCaptor<Issue> issueCaptor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(issueCaptor.capture());
        Issue saved = issueCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(IssueStatus.DONE);
        assertThat(saved.getGithubPrUrl()).isEqualTo(prUrl);

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        verify(webhookEventRepository).save(event);
    }

    @Test
    void prClosedWithoutMergeDoesNotChangeIssueStatus() {
        GitHubWebhookEvent event = buildEvent("pull_request", closedNotMergedPayload());

        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        verify(issueRepository, never()).findById(any());
        verify(issueRepository, never()).save(any());
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void prBodyWithNoClosesKeywordMarksEventProcessedWithNoStatusChange() {
        String payload = mergedPrPayload("No issue reference here", "https://github.com/org/repo/pull/99");
        GitHubWebhookEvent event = buildEvent("pull_request", payload);

        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        verify(issueRepository, never()).findById(any());
        verify(issueRepository, never()).save(any());
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void issueAlreadyDoneMarksEventProcessedWithNoChange() {
        String prUrl = "https://github.com/org/repo/pull/10";
        String payload = mergedPrPayload("Closes conductor/" + ISSUE_ID, prUrl);
        GitHubWebhookEvent event = buildEvent("pull_request", payload);
        Issue issue = buildIssue(IssueStatus.DONE);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        verify(issueRepository, never()).save(any(Issue.class));
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void issueAlreadyClosedMarksEventProcessedWithNoChange() {
        String prUrl = "https://github.com/org/repo/pull/11";
        String payload = mergedPrPayload("Closes conductor/" + ISSUE_ID, prUrl);
        GitHubWebhookEvent event = buildEvent("pull_request", payload);
        Issue issue = buildIssue(IssueStatus.CLOSED);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        verify(issueRepository, never()).save(any(Issue.class));
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void githubPrUrlIsSetOnIssueAfterSuccessfulProcessing() {
        String prUrl = "https://github.com/org/repo/pull/55";
        String payload = mergedPrPayload("fixes stuff\\nCloses conductor/" + ISSUE_ID + "\\nmore text", prUrl);
        GitHubWebhookEvent event = buildEvent("pull_request", payload);
        Issue issue = buildIssue(IssueStatus.CODE_REVIEW);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(issueRepository.save(any(Issue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        ArgumentCaptor<Issue> issueCaptor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(issueCaptor.capture());
        assertThat(issueCaptor.getValue().getGithubPrUrl()).isEqualTo(prUrl);
    }

    @Test
    void nonPullRequestEventTypeMarksProcessedWithNoIssueChange() {
        String payload = "{\"action\": \"push\"}";
        GitHubWebhookEvent event = buildEvent("push", payload);

        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        verify(issueRepository, never()).findById(any());
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void issueFromDifferentProjectIsIgnored() {
        String otherProjectId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        String prUrl = "https://github.com/org/repo/pull/77";
        String payload = mergedPrPayload("Closes conductor/" + ISSUE_ID, prUrl);

        GitHubWebhookEvent event = buildEvent("pull_request", payload);
        event.setProjectId(otherProjectId);

        Issue issue = buildIssue(IssueStatus.IN_PROGRESS);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        verify(issueRepository, never()).save(any(Issue.class));
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void attemptsAndLastAttemptedAtAreUpdated() {
        String payload = mergedPrPayload("Closes conductor/" + ISSUE_ID, "https://github.com/org/repo/pull/1");
        GitHubWebhookEvent event = buildEvent("pull_request", payload);
        event.setAttempts(2);
        Issue issue = buildIssue(IssueStatus.IN_PROGRESS);

        when(issueRepository.findById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(issueRepository.save(any(Issue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(webhookEventRepository.save(any(GitHubWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.processEvent(event);

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getLastAttemptedAt()).isNotNull();
    }
}
