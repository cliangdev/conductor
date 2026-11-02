package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CycleTimestampsTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-11-02T10:00:00Z");
    private static final OffsetDateTime T3 = OffsetDateTime.parse("2026-11-03T10:00:00Z");

    private Statechart chart;
    private WorkItem item;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/task.workflow.json")) {
            chart = Statechart.parse(new ObjectMapper().readTree(in));
        }
        item = new WorkItem();
        item.setCurrentStatus("TODO");
    }

    @Test
    void firstMoveIntoAnInProgressStatusStampsStartedAt() {
        CycleTimestamps.stamp(chart, item, "TODO", "IN_PROGRESS", T1);

        assertThat(item.getStartedAt()).isEqualTo(T1);
        assertThat(item.getCompletedAt()).isNull();
    }

    @Test
    void enteringATerminalStatusStampsCompletedAt() {
        CycleTimestamps.stamp(chart, item, "TODO", "IN_PROGRESS", T1);
        CycleTimestamps.stamp(chart, item, "IN_PROGRESS", "DONE", T2);

        assertThat(item.getStartedAt()).isEqualTo(T1);
        assertThat(item.getCompletedAt()).isEqualTo(T2);
    }

    @Test
    void stoppingAndRestartingKeepsTheOriginalStartedAt() {
        CycleTimestamps.stamp(chart, item, "TODO", "IN_PROGRESS", T1);
        CycleTimestamps.stamp(chart, item, "IN_PROGRESS", "TODO", T2);
        CycleTimestamps.stamp(chart, item, "TODO", "IN_PROGRESS", T3);

        assertThat(item.getStartedAt()).isEqualTo(T1);
    }

    @Test
    void reopeningClearsCompletedAtButKeepsStartedAt() {
        CycleTimestamps.stamp(chart, item, "TODO", "IN_PROGRESS", T1);
        CycleTimestamps.stamp(chart, item, "IN_PROGRESS", "DONE", T2);
        CycleTimestamps.stamp(chart, item, "DONE", "TODO", T3);

        assertThat(item.getCompletedAt()).isNull();
        assertThat(item.getStartedAt()).isEqualTo(T1);
    }

    @Test
    void finishingFromTodoStampsCompletionButLeavesStartedAtUnknown() {
        CycleTimestamps.stamp(chart, item, "TODO", "DONE", T1);

        assertThat(item.getCompletedAt()).isEqualTo(T1);
        assertThat(item.getStartedAt()).isNull();
    }

    @Test
    void cancellingCountsAsCompletion() {
        CycleTimestamps.stamp(chart, item, "TODO", "CANCELLED", T1);

        assertThat(item.getCompletedAt()).isEqualTo(T1);
    }

    @Test
    void aMoveToTheSameStatusOrWithNoStatechartChangesNothing() {
        CycleTimestamps.stamp(chart, item, "TODO", "TODO", T1);
        CycleTimestamps.stamp(null, item, "TODO", "DONE", T1);

        assertThat(item.getStartedAt()).isNull();
        assertThat(item.getCompletedAt()).isNull();
    }
}
