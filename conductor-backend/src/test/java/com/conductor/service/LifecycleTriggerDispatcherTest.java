package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.WorkItemRepository;
import com.conductor.workflow.lifecycle.StatechartTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the generalized lifecycle trigger cascade (#240 §3). The workflow service is mocked; its
 * {@code applySystemTransition} stub mutates the Work Item's status exactly as the real one does, so the
 * dispatcher's cascade loop, visited-set cycle guard, and re-entrancy guard are exercised in isolation.
 */
class LifecycleTriggerDispatcherTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String WORK_ITEM_ID = "wi-1";

    private WorkItemRepository workItemRepository;
    private WorkItemWorkflowService workItemWorkflowService;
    private WorkItemService workItemService;
    private LifecycleTriggerDispatcher dispatcher;
    private WorkItem workItem;

    @BeforeEach
    void setUp() {
        workItemRepository = mock(WorkItemRepository.class);
        workItemWorkflowService = mock(WorkItemWorkflowService.class);
        workItemService = mock(WorkItemService.class);
        dispatcher = new LifecycleTriggerDispatcher(workItemRepository, workItemWorkflowService, workItemService);

        workItem = new WorkItem();
        workItem.setId(WORK_ITEM_ID);
        Project project = new Project();
        project.setId(PROJECT_ID);
        workItem.setProject(project);
        workItem.setCurrentStatus("A");
        when(workItemRepository.findById(WORK_ITEM_ID)).thenReturn(Optional.of(workItem));
    }

    /** Stub applySystemTransition to walk the Work Item through {@code targets}, then return empty. */
    private void advanceThrough(String... targets) {
        Deque<String> queue = new ArrayDeque<>();
        for (String t : targets) {
            queue.add(t);
        }
        when(workItemWorkflowService.applySystemTransition(eq(PROJECT_ID), eq(workItem),
                eq(WorkItemWorkflowService.TRIGGER_STATUS_CHANGED)))
                .thenAnswer(inv -> {
                    if (queue.isEmpty()) {
                        return Optional.empty();
                    }
                    String next = queue.poll();
                    workItem.setCurrentStatus(next);
                    return Optional.of(transitionTo(next));
                });
    }

    private static StatechartTransition transitionTo(String to) {
        return new StatechartTransition(null, to, null, false, null, null,
                WorkItemWorkflowService.TRIGGER_STATUS_CHANGED, null);
    }

    private NotificationEvent statusChangedEvent() {
        return NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", WORK_ITEM_ID, "workItemTitle", "T"));
    }

    @Test
    void ignoresNonStatusChangedEvents() {
        dispatcher.onConductorEvent(NotificationEvent.of(EventType.COMMENT_ADDED, PROJECT_ID,
                Map.of("workItemId", WORK_ITEM_ID)));
        verify(workItemRepository, never()).findById(any());
    }

    @Test
    void noopWhenEventCarriesNoWorkItemId() {
        dispatcher.onConductorEvent(NotificationEvent.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemTitle", "T")));
        verify(workItemRepository, never()).findById(any());
    }

    @Test
    void singleHopAdvancesSavesAndRepublishes() {
        advanceThrough("B");
        dispatcher.onConductorEvent(statusChangedEvent());

        assertThat(workItem.getCurrentStatus()).isEqualTo("B");
        verify(workItemRepository, times(1)).save(workItem);
        verify(workItemService, times(1)).publishStatusChanged(PROJECT_ID, workItem, "A", "B", null);
    }

    @Test
    void cascadesAcrossMultipleHops() {
        advanceThrough("B", "C");
        dispatcher.onConductorEvent(statusChangedEvent());

        assertThat(workItem.getCurrentStatus()).isEqualTo("C");
        verify(workItemRepository, times(2)).save(workItem);
        verify(workItemService).publishStatusChanged(PROJECT_ID, workItem, "A", "B", null);
        verify(workItemService).publishStatusChanged(PROJECT_ID, workItem, "B", "C", null);
    }

    @Test
    void noMatchingTransitionIsANoopButStillLoadsOnce() {
        advanceThrough(); // applySystemTransition returns empty immediately
        dispatcher.onConductorEvent(statusChangedEvent());

        assertThat(workItem.getCurrentStatus()).isEqualTo("A");
        verify(workItemRepository, never()).save(any());
        verify(workItemService, never()).publishStatusChanged(any(), any(), any(), any(), any());
    }

    @Test
    void terminatesOnStatechartCycleWithoutPersistingTheCyclicHop() {
        // A -> B -> A -> B -> ... : the visited-set check runs BEFORE persist/publish, so only the acyclic
        // hop (A->B) is committed; the cyclic hop (B->A) is detected and reverted, not persisted.
        advanceThrough("B", "A", "B", "A", "B", "A");
        dispatcher.onConductorEvent(statusChangedEvent());

        verify(workItemRepository, times(1)).save(workItem);
        verify(workItemService, times(1)).publishStatusChanged(any(), any(), any(), any(), any());
        // The cyclic advance to A was reverted, so the item rests at the last non-repeating status.
        assertThat(workItem.getCurrentStatus()).isEqualTo("B");
    }

    @Test
    void reentrancyGuardMakesNestedEventANoop() {
        advanceThrough("B");
        // Simulate the real dispatch fan-out: publishing the per-hop event re-enters the dispatcher.
        doAnswer(inv -> {
            dispatcher.onConductorEvent(statusChangedEvent());
            return null;
        }).when(workItemService).publishStatusChanged(any(), any(), any(), any(), any());

        dispatcher.onConductorEvent(statusChangedEvent());

        // The nested (guarded) call must not re-load or re-advance: exactly one outer load.
        verify(workItemRepository, times(1)).findById(WORK_ITEM_ID);
        verify(workItemRepository, times(1)).save(workItem);
    }
}
