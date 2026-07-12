package com.conductor.knowledge;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.service.ProjectSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeEventTapTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock private KnowledgeIngestionService ingestionService;
    @Mock private ProjectSettingsService projectSettingsService;

    private KnowledgeEventTap tap;

    @BeforeEach
    void setUp() {
        tap = new KnowledgeEventTap(ingestionService, projectSettingsService, new ObjectMapper());
    }

    private NotificationEvent statusChangedEvent() {
        return NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, Map.of(
                "workItemId", "wi-1",
                "workItemTitle", "Ship the thing",
                "fromStatus", "IN_PROGRESS",
                "toStatus", "CODE_REVIEW"));
    }

    @Test
    void knowledgeEnabled_submitsNormalizedEnvelope() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);

        tap.onConductorEvent(statusChangedEvent());

        ArgumentCaptor<KnowledgeSubmission> captor = ArgumentCaptor.forClass(KnowledgeSubmission.class);
        verify(ingestionService).submit(captor.capture());
        KnowledgeSubmission submission = captor.getValue();
        assertThat(submission.projectId()).isEqualTo(PROJECT_ID);
        assertThat(submission.sourceType()).isEqualTo("conductor.work_item.status_changed");
        assertThat(submission.sourceRef()).isEqualTo("conductor:wi-1");
        assertThat(submission.title()).isEqualTo("Ship the thing");
        assertThat(submission.origin().kind()).isEqualTo("EVENT_TAP");
        assertThat(submission.payload()).contains("IN_PROGRESS", "CODE_REVIEW", "wi-1");
        assertThat(submission.dedupKey()).isEqualTo("work-item-status-changed:proj-1:wi-1:IN_PROGRESS->CODE_REVIEW");
    }

    @Test
    void knowledgeDisabled_doesNotSubmit() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);

        tap.onConductorEvent(statusChangedEvent());

        verify(ingestionService, never()).submit(any());
    }

    @Test
    void nonStatusChangedEvent_isIgnoredWithoutCheckingSettings() {
        NotificationEvent event = NotificationEvent.of(EventType.MEMBER_JOINED, PROJECT_ID,
                Map.of("memberName", "Alice"));

        tap.onConductorEvent(event);

        verify(projectSettingsService, never()).isKnowledgeEnabled(anyString());
        verify(ingestionService, never()).submit(any());
    }

    @Test
    void exceptionFromIngestionService_propagatesToCaller() {
        // KnowledgeEventTap itself does not swallow exceptions — NotificationDispatcher's call site
        // wraps it in try/catch (see the other three consumers), matching that precedent exactly.
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        doThrow(new RuntimeException("boom")).when(ingestionService).submit(any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> tap.onConductorEvent(statusChangedEvent()));
    }
}
