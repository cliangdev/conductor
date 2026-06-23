package com.conductor.service;

import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookRetrySchedulerTest {

    private static final int MAX_ATTEMPTS = 5;

    @Mock private WebhookEventRepository eventRepository;
    @Mock private WebhookDispatchService dispatchService;

    private WebhookRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WebhookRetryScheduler(eventRepository, dispatchService);
    }

    private WebhookEvent failed(String id, int attempts, OffsetDateTime lastAttemptedAt) {
        WebhookEvent e = new WebhookEvent();
        e.setId(id);
        e.setStatus(WebhookEventStatus.FAILED);
        e.setAttempts(attempts);
        e.setLastAttemptedAt(lastAttemptedAt);
        return e;
    }

    /**
     * #7 dead-letter: the real {@code findRetryable} has NO {@code attempts < maxAttempts} bound, so an event
     * that has reached MAX_ATTEMPTS IS returned to the scheduler — which transitions it to DEAD rather than
     * leaving it stuck at FAILED. We feed a realistic candidate (FAILED, old lastAttemptedAt, attempts ==
     * MAX_ATTEMPTS) — the row the NEW query returns and the OLD one wrongly filtered out.
     */
    @Test
    void eventAtMaxAttempts_isMarkedDead_notRetried() {
        WebhookEvent exhausted = failed("dead-1", MAX_ATTEMPTS, OffsetDateTime.now().minusHours(1));
        when(eventRepository.findRetryable(anyInt(), any())).thenReturn(List.of(exhausted));

        scheduler.retryFailedEvents();

        assertThat(exhausted.getStatus()).isEqualTo(WebhookEventStatus.DEAD);
        verify(eventRepository).save(exhausted);
        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void eventUnderMaxAttempts_withElapsedBackoff_isRetried() {
        // attempts=2 → backoff 2^2 = 4 min; last attempt 10 min ago → ready.
        WebhookEvent retryable = failed("retry-1", 2, OffsetDateTime.now().minusMinutes(10));
        when(eventRepository.findRetryable(anyInt(), any())).thenReturn(List.of(retryable));

        scheduler.retryFailedEvents();

        verify(dispatchService).dispatch(retryable);
        assertThat(retryable.getStatus()).isEqualTo(WebhookEventStatus.FAILED); // not touched by scheduler
    }

    @Test
    void eventUnderMaxAttempts_withinBackoffWindow_isSkipped() {
        // attempts=4 → backoff 2^4 = 16 min; last attempt 1 min ago → too soon, skip (no retry, no dead).
        WebhookEvent notReady = failed("wait-1", 4, OffsetDateTime.now().minusMinutes(1));
        when(eventRepository.findRetryable(anyInt(), any())).thenReturn(List.of(notReady));

        scheduler.retryFailedEvents();

        verify(dispatchService, never()).dispatch(any());
        verify(eventRepository, never()).save(any());
        assertThat(notReady.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
    }

    @Test
    void mixedBatch_deadAndRetryHandledIndependently() {
        WebhookEvent exhausted = failed("dead-2", MAX_ATTEMPTS + 1, OffsetDateTime.now().minusHours(2));
        WebhookEvent retryable = failed("retry-2", 1, OffsetDateTime.now().minusMinutes(30));
        when(eventRepository.findRetryable(anyInt(), any())).thenReturn(List.of(exhausted, retryable));

        scheduler.retryFailedEvents();

        ArgumentCaptor<WebhookEvent> saved = ArgumentCaptor.forClass(WebhookEvent.class);
        verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("dead-2");
        assertThat(saved.getValue().getStatus()).isEqualTo(WebhookEventStatus.DEAD);
        verify(dispatchService).dispatch(retryable);
    }
}
