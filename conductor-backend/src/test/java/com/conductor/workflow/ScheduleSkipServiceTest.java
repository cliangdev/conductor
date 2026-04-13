package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowSchedule;
import com.conductor.entity.WorkflowScheduleSkip;
import com.conductor.repository.WorkflowScheduleSkipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleSkipServiceTest {

    @Mock
    private WorkflowScheduleSkipRepository skipRepository;

    private WorkflowSchedule buildSchedule(String scheduleId, WorkflowDefinition workflow) {
        WorkflowSchedule s = new WorkflowSchedule();
        s.setId(scheduleId);
        s.setWorkflow(workflow);
        s.setCronExpression("0 * * * *");
        return s;
    }

    @Test
    void skipRowContainsCorrectScheduleIdReasonAndRunId() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf-1");
        WorkflowSchedule schedule = buildSchedule("sched-1", def);

        WorkflowScheduleSkip skip = new WorkflowScheduleSkip();
        skip.setSchedule(schedule);
        skip.setSkippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        skip.setReason("Workflow run already in progress (concurrency: single)");
        skip.setRunId("run-active");

        skipRepository.save(skip);

        ArgumentCaptor<WorkflowScheduleSkip> captor = ArgumentCaptor.forClass(WorkflowScheduleSkip.class);
        verify(skipRepository).save(captor.capture());
        WorkflowScheduleSkip saved = captor.getValue();

        assertThat(saved.getSchedule().getId()).isEqualTo("sched-1");
        assertThat(saved.getReason()).contains("concurrency: single");
        assertThat(saved.getRunId()).isEqualTo("run-active");
        assertThat(saved.getSkippedAt()).isNotNull();
    }

    @Test
    void skipsReturnedOrderedBySkippedAtDesc() {
        OffsetDateTime t1 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        OffsetDateTime t2 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        OffsetDateTime t3 = OffsetDateTime.now(ZoneOffset.UTC);

        WorkflowScheduleSkip skip1 = makeSkip("s1", t1);
        WorkflowScheduleSkip skip2 = makeSkip("s2", t2);
        WorkflowScheduleSkip skip3 = makeSkip("s3", t3);

        when(skipRepository.findByScheduleIdOrderBySkippedAtDesc("sched-1"))
                .thenReturn(List.of(skip3, skip2, skip1));

        List<WorkflowScheduleSkip> result = skipRepository.findByScheduleIdOrderBySkippedAtDesc("sched-1");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getSkippedAt()).isEqualTo(t3);
        assertThat(result.get(1).getSkippedAt()).isEqualTo(t2);
        assertThat(result.get(2).getSkippedAt()).isEqualTo(t1);
    }

    @Test
    void skipWithNullRunIdIsAllowed() {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId("wf-2");
        WorkflowSchedule schedule = buildSchedule("sched-2", def);

        WorkflowScheduleSkip skip = new WorkflowScheduleSkip();
        skip.setSchedule(schedule);
        skip.setSkippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        skip.setReason("some reason");
        // runId is intentionally null

        skipRepository.save(skip);

        ArgumentCaptor<WorkflowScheduleSkip> captor = ArgumentCaptor.forClass(WorkflowScheduleSkip.class);
        verify(skipRepository).save(captor.capture());
        assertThat(captor.getValue().getRunId()).isNull();
    }

    private WorkflowScheduleSkip makeSkip(String id, OffsetDateTime skippedAt) {
        WorkflowScheduleSkip skip = new WorkflowScheduleSkip();
        skip.setId(id);
        skip.setSkippedAt(skippedAt);
        return skip;
    }
}
