package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.repository.BlockerRow;
import com.conductor.repository.WorkItemAgentSessionRepository;
import com.conductor.repository.WorkItemDependencyRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.WorkItemSignalService.Signals;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** The derived signals against the real TASK and ENGINEERING statecharts, a fixed clock and mocked reads. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkItemSignalServiceTest {

    private static final String PROJECT = "proj-1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T12:00:00Z");

    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemDependencyRepository dependencyRepository;
    @Mock private WorkItemAgentSessionRepository sessionRepository;
    @Mock private WorkItemWorkflowService workflowService;

    private WorkItemSignalService service;
    private Project project;
    private int sequence = 1;

    @BeforeEach
    void setUp() throws Exception {
        Statechart task = chart("/schema/examples/task.workflow.json");
        Statechart engineering = chart("/schema/examples/engineering.workflow.json");
        when(workflowService.resolveFor(eq(PROJECT), anyString(), any()))
                .thenAnswer(inv -> "TASK".equals(inv.getArgument(1)) ? task : engineering);
        project = new Project();
        project.setId(PROJECT);
        project.setKey("COND");
        service = new WorkItemSignalService(workItemRepository, dependencyRepository, sessionRepository,
                workflowService, Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
    }

    private Statechart chart(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        }
    }

    private WorkItem task(String id, String status, OffsetDateTime updatedAt) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProject(project);
        w.setWorkflow("TASK");
        w.setWorkflowVersion(1);
        w.setType("TASK");
        w.setTitle("Task " + id);
        w.setCurrentStatus(status);
        w.setSequenceNumber(sequence++);
        w.setUpdatedAt(updatedAt);
        return w;
    }

    private static BlockerRow blocker(String blockedId, String blockerId, String status) {
        return new BlockerRow(blockedId, blockerId, "COND", 99, "Blocker " + blockerId, status, "TASK", 1);
    }

    private static WorkItemAgentSession session(String itemId, String activity, OffsetDateTime heartbeat) {
        WorkItemAgentSession s = new WorkItemAgentSession();
        s.setWorkItemId(itemId);
        s.setSessionKey("s-" + itemId);
        s.setAgentLabel("claude");
        s.setActivity(activity);
        s.setLastHeartbeatAt(heartbeat);
        return s;
    }

    private Signals signals(WorkItem item) {
        return service.signalsFor(PROJECT, List.of(item)).get(item.getId());
    }

    @Test
    void anOpenItemWithNoBlockersIsReadyAndNotBlocked() {
        WorkItem a = task("a", "TODO", NOW);

        Signals s = signals(a);

        assertThat(s.ready()).isTrue();
        assertThat(s.blocked()).isFalse();
        assertThat(s.blockedBy()).isEmpty();
        assertThat(s.stale()).isFalse();
        assertThat(s.needsInput()).isFalse();
    }

    @Test
    void anItemWithAnUnfinishedBlockerIsBlockedAndNotReadyAndNamesTheBlocker() {
        WorkItem b = task("b", "TODO", NOW);
        when(dependencyRepository.findBlockersFor(anyCollection()))
                .thenReturn(List.of(blocker("b", "a", "IN_PROGRESS")));

        Signals s = signals(b);

        assertThat(s.blocked()).isTrue();
        assertThat(s.ready()).isFalse();
        assertThat(s.blockedBy()).extracting(WorkItemSignalService.Blocker::id).containsExactly("a");
        assertThat(s.blockedBy().get(0).displayId()).isEqualTo("COND-99");
        assertThat(s.blockedBy().get(0).title()).isEqualTo("Blocker a");
    }

    @Test
    void aBlockerInATerminalStatusUnblocksWhetherDoneOrCancelled() {
        WorkItem b = task("b", "TODO", NOW);
        when(dependencyRepository.findBlockersFor(anyCollection()))
                .thenReturn(List.of(blocker("b", "a", "DONE"), blocker("b", "c", "CANCELLED")));

        Signals s = signals(b);

        assertThat(s.blocked()).isFalse();
        assertThat(s.blockedBy()).isEmpty();
        assertThat(s.ready()).isTrue();
    }

    @Test
    void aBlockerIsJudgedInItsOwnWorkflowNotTheBlockedItems() {
        WorkItem b = task("b", "TODO", NOW);
        // ENGINEERING's DRAFT is not terminal there, though it is no TASK status at all.
        when(dependencyRepository.findBlockersFor(anyCollection())).thenReturn(List.of(
                new BlockerRow("b", "prd", "COND", 1, "A PRD", "DRAFT", "ENGINEERING", 1)));

        assertThat(signals(b).blocked()).isTrue();
    }

    @Test
    void aTerminalOrInProgressItemIsNeverReady() {
        assertThat(signals(task("done", "DONE", NOW)).ready()).isFalse();
        assertThat(signals(task("going", "IN_PROGRESS", NOW)).ready()).isFalse();
    }

    @Test
    void anInProgressItemUntouchedForMoreThanSevenDaysIsStale() {
        WorkItem old = task("old", "IN_PROGRESS", NOW.minusDays(8));
        WorkItem fresh = task("fresh", "IN_PROGRESS", NOW.minusDays(6));
        WorkItem openAndOld = task("open", "TODO", NOW.minusDays(30));

        Map<String, Signals> result = service.signalsFor(PROJECT, List.of(old, fresh, openAndOld));

        assertThat(result.get("old").stale()).isTrue();
        assertThat(result.get("fresh").stale()).isFalse();
        // Only in-progress items go stale; an old open item is just waiting.
        assertThat(result.get("open").stale()).isFalse();
    }

    @Test
    void aLiveWorkingSessionKeepsAnOldItemFromGoingStale() {
        WorkItem old = task("old", "IN_PROGRESS", NOW.minusDays(10));
        when(sessionRepository.findLatestOpenByWorkItem(anyCollection())).thenReturn(List.of(
                session("old", "WORKING", NOW.minusDays(1))));

        Signals s = signals(old);

        assertThat(s.stale()).isFalse();
        assertThat(s.agentActivity()).isNotNull();
        assertThat(s.agentActivity().getActivity()).isEqualTo("WORKING");
    }

    @Test
    void aWorkingSessionThatStoppedHeartbeatingSevenDaysAgoDoesNotSaveTheItem() {
        WorkItem old = task("old", "IN_PROGRESS", NOW.minusDays(10));
        when(sessionRepository.findLatestOpenByWorkItem(anyCollection())).thenReturn(List.of(
                session("old", "WORKING", NOW.minusDays(8))));

        assertThat(signals(old).stale()).isTrue();
    }

    @Test
    void aNeedsInputSessionDoesNotCountAsAnAgentWorking() {
        WorkItem old = task("old", "IN_PROGRESS", NOW.minusDays(10));
        when(sessionRepository.findLatestOpenByWorkItem(anyCollection())).thenReturn(List.of(
                session("old", "NEEDS_INPUT", NOW.minusHours(1))));

        Signals s = signals(old);

        assertThat(s.stale()).isTrue();
        assertThat(s.needsInput()).isTrue();
    }

    @Test
    void needsInputIsTrueWhenTheItemsOwnLatestOpenSessionAsks() {
        WorkItem a = task("a", "IN_PROGRESS", NOW);
        when(sessionRepository.findLatestOpenByWorkItem(anyCollection())).thenReturn(List.of(
                session("a", "NEEDS_INPUT", NOW)));

        assertThat(signals(a).needsInput()).isTrue();
    }

    @Test
    void aParentShowsNeedsInputWhenADescendantAsks() {
        WorkItem prd = task("prd", "TODO", NOW);
        when(workItemRepository.countChildrenGrouped(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {"prd", 1L}));
        when(workItemRepository.findSubtreeIds("prd")).thenReturn(List.of("prd", "epic", "leaf"));
        when(sessionRepository.findLatestOpenByWorkItem(anyCollection())).thenReturn(List.of(
                session("leaf", "NEEDS_INPUT", NOW)));

        Signals s = signals(prd);

        assertThat(s.needsInput()).isTrue();
        // The PRD's own agentActivity is its own session only.
        assertThat(s.agentActivity()).isNull();
    }

    @Test
    void aLeafDoesNotLookAtDescendantsAndAChildrenWithWorkingSessionIsNotNeedsInput() {
        WorkItem prd = task("prd", "TODO", NOW);
        when(workItemRepository.countChildrenGrouped(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[] {"prd", 1L}));
        when(workItemRepository.findSubtreeIds("prd")).thenReturn(List.of("prd", "leaf"));
        when(sessionRepository.findLatestOpenByWorkItem(anyCollection())).thenReturn(List.of(
                session("leaf", "WORKING", NOW)));

        assertThat(signals(prd).needsInput()).isFalse();
    }

    @Test
    void anEmptyPageYieldsAnEmptyMapWithoutQueries() {
        assertThat(service.signalsFor(PROJECT, List.of())).isEmpty();
    }
}
