package com.conductor.service;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.GitHubWebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookRetrySchedulerTest {

    @Mock
    private GitHubWebhookEventRepository webhookEventRepository;

    @Mock
    private GitHubWebhookProcessor webhookProcessor;

    private GitHubWebhookRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new GitHubWebhookRetryScheduler(webhookEventRepository, webhookProcessor);
    }

    private GitHubWebhookEvent buildFailedEvent(String id, int attempts, OffsetDateTime lastAttemptedAt) {
        GitHubWebhookEvent event = new GitHubWebhookEvent();
        event.setId(id);
        event.setStatus(WebhookEventStatus.FAILED);
        event.setAttempts(attempts);
        event.setLastAttemptedAt(lastAttemptedAt);
        return event;
    }

    @Test
    void processesEventThatIsReadyForRetry() {
        // attempt=1 → backoff = 2^1 = 2 minutes; last attempted 3 minutes ago → ready
        GitHubWebhookEvent event = buildFailedEvent("evt-1", 1, OffsetDateTime.now().minusMinutes(3));
        when(webhookEventRepository.findRetryableEvents(anyInt(), any(OffsetDateTime.class)))
                .thenReturn(List.of(event));

        scheduler.retryFailedEvents();

        verify(webhookProcessor).processEvent(event);
    }

    @Test
    void skipsEventStillWithinBackoffWindow() {
        // attempt=2 → backoff = 2^2 = 4 minutes; last attempted only 1 minute ago → not ready
        GitHubWebhookEvent event = buildFailedEvent("evt-2", 2, OffsetDateTime.now().minusMinutes(1));
        when(webhookEventRepository.findRetryableEvents(anyInt(), any(OffsetDateTime.class)))
                .thenReturn(List.of(event));

        scheduler.retryFailedEvents();

        verify(webhookProcessor, never()).processEvent(any());
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void marksEventDeadWhenAttemptsReachMaximum() {
        // attempts=5 → MAX_ATTEMPTS reached; past the backoff window
        GitHubWebhookEvent event = buildFailedEvent("evt-3", 5, OffsetDateTime.now().minusHours(1));
        when(webhookEventRepository.findRetryableEvents(anyInt(), any(OffsetDateTime.class)))
                .thenReturn(List.of(event));

        scheduler.retryFailedEvents();

        verify(webhookProcessor, never()).processEvent(any());
        ArgumentCaptor<GitHubWebhookEvent> captor = ArgumentCaptor.forClass(GitHubWebhookEvent.class);
        verify(webhookEventRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WebhookEventStatus.DEAD);
    }

    @Test
    void processesMultipleReadyEventsAndSkipsOthers() {
        // ready: attempt=0, lastAttemptedAt null (never attempted before — should process)
        GitHubWebhookEvent readyEvent = buildFailedEvent("evt-ready", 0, null);
        // not ready: attempt=3 → backoff = 8 minutes; last attempted 2 minutes ago
        GitHubWebhookEvent notReadyEvent = buildFailedEvent("evt-not-ready", 3, OffsetDateTime.now().minusMinutes(2));
        // dead: attempts at max
        GitHubWebhookEvent deadEvent = buildFailedEvent("evt-dead", 5, OffsetDateTime.now().minusHours(2));

        when(webhookEventRepository.findRetryableEvents(anyInt(), any(OffsetDateTime.class)))
                .thenReturn(List.of(readyEvent, notReadyEvent, deadEvent));

        scheduler.retryFailedEvents();

        verify(webhookProcessor).processEvent(readyEvent);
        verify(webhookProcessor, never()).processEvent(notReadyEvent);
        verify(webhookProcessor, never()).processEvent(deadEvent);

        ArgumentCaptor<GitHubWebhookEvent> savedCaptor = ArgumentCaptor.forClass(GitHubWebhookEvent.class);
        verify(webhookEventRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getId()).isEqualTo("evt-dead");
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(WebhookEventStatus.DEAD);
    }

    @Test
    void doesNothingWhenNoRetryableEvents() {
        when(webhookEventRepository.findRetryableEvents(anyInt(), any(OffsetDateTime.class)))
                .thenReturn(List.of());

        scheduler.retryFailedEvents();

        verify(webhookProcessor, never()).processEvent(any());
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void eventWithNullLastAttemptedAtAndZeroAttemptsIsProcessed() {
        // Never attempted (null lastAttemptedAt) — should always process
        GitHubWebhookEvent event = buildFailedEvent("evt-null", 0, null);
        when(webhookEventRepository.findRetryableEvents(anyInt(), any(OffsetDateTime.class)))
                .thenReturn(List.of(event));

        scheduler.retryFailedEvents();

        verify(webhookProcessor).processEvent(event);
    }
}
