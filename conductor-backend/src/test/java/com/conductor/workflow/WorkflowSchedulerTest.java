package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowSchedule;
import com.conductor.entity.WorkflowScheduleSkip;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.repository.WorkflowScheduleSkipRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowSchedulerTest {

    @Mock
    private WorkflowScheduleRepository scheduleRepository;

    @Mock
    private WorkflowScheduleSkipRepository skipRepository;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowTriggerService triggerService;

    private WorkflowScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WorkflowScheduler(scheduleRepository, skipRepository, runRepository,
                triggerService, new ObjectMapper());
    }

    private WorkflowDefinition workflow(String id, String yaml) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(id);
        def.setYaml(yaml);
        def.setEnabled(true);
        return def;
    }

    private WorkflowSchedule schedule(WorkflowDefinition workflow, String cron) {
        WorkflowSchedule s = new WorkflowSchedule();
        s.setId("sched-1");
        s.setWorkflow(workflow);
        s.setCronExpression(cron);
        s.setEnabled(true);
        s.setNextRunAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        return s;
    }

    @Test
    void dueScheduleFiresTriggger() {
        WorkflowDefinition def = workflow("wf-1", "on:\n  schedule:\n    cron: '0 * * * *'\njobs: {}");
        WorkflowSchedule sched = schedule(def, "0 * * * *");
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");

        when(scheduleRepository.findByEnabledTrueAndNextRunAtBefore(any())).thenReturn(List.of(sched));
        when(triggerService.fireTrigger(eq("wf-1"), eq("schedule"), anyString())).thenReturn(run);
        when(triggerService.computeNextRun(anyString(), any())).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        scheduler.poll();

        verify(triggerService).fireTrigger(eq("wf-1"), eq("schedule"), anyString());
        verify(scheduleRepository).save(sched);
        assertThat(sched.getLastRunAt()).isNotNull();
        assertThat(sched.getNextRunAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void disabledWorkflowDoesNotFire() {
        WorkflowDefinition def = workflow("wf-2", "on:\n  schedule:\n    cron: '0 * * * *'\njobs: {}");
        def.setEnabled(false);
        WorkflowSchedule sched = schedule(def, "0 * * * *");

        when(scheduleRepository.findByEnabledTrueAndNextRunAtBefore(any())).thenReturn(List.of(sched));

        scheduler.poll();

        verify(triggerService, never()).fireTrigger(any(), any(), any());
    }

    @Test
    void concurrencySingleWithActiveRunRecordsSkip() {
        String yaml = "concurrency: single\non:\n  schedule:\n    cron: '0 * * * *'\njobs: {}";
        WorkflowDefinition def = workflow("wf-3", yaml);
        WorkflowSchedule sched = schedule(def, "0 * * * *");

        WorkflowRun activeRun = new WorkflowRun();
        activeRun.setId("active-run-1");

        when(scheduleRepository.findByEnabledTrueAndNextRunAtBefore(any())).thenReturn(List.of(sched));
        when(runRepository.findByWorkflowIdAndStatusIn(eq("wf-3"),
                argThat(c -> c.containsAll(List.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.PENDING)))
        )).thenReturn(List.of(activeRun));
        when(triggerService.computeNextRun(anyString(), any())).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        scheduler.poll();

        verify(triggerService, never()).fireTrigger(any(), any(), any());

        ArgumentCaptor<WorkflowScheduleSkip> skipCaptor = ArgumentCaptor.forClass(WorkflowScheduleSkip.class);
        verify(skipRepository).save(skipCaptor.capture());
        WorkflowScheduleSkip skip = skipCaptor.getValue();
        assertThat(skip.getSchedule()).isEqualTo(sched);
        assertThat(skip.getRunId()).isEqualTo("active-run-1");
        assertThat(skip.getReason()).contains("concurrency");
    }

    @Test
    void missedScheduleFiresExactlyOnce() {
        WorkflowDefinition def = workflow("wf-4", "on:\n  schedule:\n    cron: '0 * * * *'\njobs: {}");
        // Schedule overdue by multiple intervals
        WorkflowSchedule sched = schedule(def, "0 * * * *");
        sched.setNextRunAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(5));
        WorkflowRun run = new WorkflowRun();
        run.setId("run-4");

        // Repository returns only ONE schedule (the scheduler fires it once, not once-per-missed-interval)
        when(scheduleRepository.findByEnabledTrueAndNextRunAtBefore(any())).thenReturn(List.of(sched));
        when(triggerService.fireTrigger(any(), any(), any())).thenReturn(run);
        when(triggerService.computeNextRun(anyString(), any())).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        scheduler.poll();

        // Fired exactly once regardless of how many intervals were missed
        verify(triggerService, times(1)).fireTrigger(eq("wf-4"), eq("schedule"), anyString());
    }

    @Test
    void lastRunAtAndNextRunAtUpdatedAfterFiring() {
        WorkflowDefinition def = workflow("wf-5", "on:\n  schedule:\n    cron: '* * * * *'\njobs: {}");
        WorkflowSchedule sched = schedule(def, "* * * * *");
        OffsetDateTime nextRun = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-5");

        when(scheduleRepository.findByEnabledTrueAndNextRunAtBefore(any())).thenReturn(List.of(sched));
        when(triggerService.fireTrigger(any(), any(), any())).thenReturn(run);
        when(triggerService.computeNextRun(anyString(), any())).thenReturn(nextRun);

        scheduler.poll();

        assertThat(sched.getLastRunAt()).isNotNull();
        assertThat(sched.getNextRunAt()).isEqualTo(nextRun);
    }
}
