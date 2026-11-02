package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.DeliveryOverviewService.ActiveItem;
import com.conductor.service.DeliveryOverviewService.Overview;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.service.ThroughputForecastService.Forecast;
import com.conductor.service.ThroughputForecastService.State;
import com.conductor.service.WorkItemSignalService.Signals;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which items the overview calls active, and where the attention lists draw their members from. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeliveryOverviewServiceTest {

    private static final String PROJECT = "proj-1";

    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemHierarchyService hierarchyService;
    @Mock private WorkItemSignalService signalService;
    @Mock private WorkItemHealthService healthService;
    @Mock private ThroughputForecastService forecastService;
    @Mock private MilestoneService milestoneService;
    @Mock private WorkItemWorkflowService workflowService;

    private DeliveryOverviewService service;
    private Project project;
    private int sequence = 1;
    private final Map<String, Signals> signals = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        Statechart task = chart("/schema/examples/task.workflow.json");
        Statechart engineering = chart("/schema/examples/engineering.workflow.json");
        when(workflowService.resolveFor(eq(PROJECT), anyString(), any()))
                .thenAnswer(inv -> "TASK".equals(inv.getArgument(1)) ? task : engineering);
        project = new Project();
        project.setId(PROJECT);
        project.setKey("COND");
        service = new DeliveryOverviewService(workItemRepository, hierarchyService, signalService, healthService,
                forecastService, milestoneService, workflowService);
        when(signalService.signalsFor(eq(PROJECT), anyList())).thenAnswer(inv -> {
            Map<String, Signals> out = new HashMap<>();
            for (WorkItem w : inv.<List<WorkItem>>getArgument(1)) {
                out.put(w.getId(), signals.getOrDefault(w.getId(), calm()));
            }
            return out;
        });
        when(hierarchyService.progressFor(eq(PROJECT), anyCollection()))
                .thenReturn(Map.of());
        when(forecastService.forecast(eq(PROJECT), any(WorkItem.class)))
                .thenReturn(new Forecast(State.NO_PLAN, null, 0, 0, null, null, null, null, null, null, null));
        when(healthService.current(eq(PROJECT), anyCollection(), anyMap())).thenReturn(Map.of());
        when(milestoneService.list(PROJECT)).thenReturn(List.of());
    }

    private Statechart chart(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        }
    }

    private static Signals calm() {
        return new Signals(false, List.of(), false, false, false, null);
    }

    private WorkItem item(String id, String type, String workflow, String status, String parentId) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProject(project);
        w.setType(type);
        w.setTitle(id);
        w.setWorkflow(workflow);
        w.setWorkflowVersion(1);
        w.setCurrentStatus(status);
        w.setParentWorkItemId(parentId);
        w.setSequenceNumber(sequence++);
        return w;
    }

    private static WorkItemAgentSession session(String itemId, String activity) {
        WorkItemAgentSession s = new WorkItemAgentSession();
        s.setWorkItemId(itemId);
        s.setActivity(activity);
        s.setAgentLabel("coder");
        return s;
    }

    private void load(WorkItem... items) {
        when(workItemRepository.findByProjectFiltered(eq(PROJECT), any(), any(), any(), any(), any(),
                eq(false), any(), any(), any())).thenReturn(new ArrayList<>(List.of(items)));
    }

    @Test
    void activeItemsAreOpenTopLevelPrdsAndTopLevelItemsWithChildren() {
        WorkItem prd = item("prd", "PRD", "ENGINEERING", "DRAFT", null);
        WorkItem finishedPrd = item("old", "PRD", "ENGINEERING", "DONE", null);
        WorkItem post = item("post", "POST", "ENGINEERING", "DRAFT", null);
        WorkItem epic = item("epic", "TASK", "TASK", "TODO", null);
        WorkItem child = item("child", "TASK", "TASK", "TODO", "epic");
        load(prd, finishedPrd, post, epic, child);
        when(hierarchyService.progressFor(eq(PROJECT), anyCollection()))
                .thenReturn(Map.of("prd", new Progress(4, 1, 25, 2, 1)));

        Overview overview = service.overview(PROJECT);

        assertThat(overview.activeItems()).extracting(a -> a.item().item().getId())
                .containsExactlyInAnyOrder("prd", "epic");
        ActiveItem active = overview.activeItems().stream()
                .filter(a -> a.item().item().getId().equals("prd")).findFirst().orElseThrow();
        assertThat(active.item().statusLabel()).isNotBlank();
        assertThat(active.progress().percent()).isEqualTo(25);
        assertThat(active.forecast().state()).isEqualTo(State.NO_PLAN);
        assertThat(active.signals()).isNotNull();
        // A root with no progress entry still gets an (empty) rollup.
        assertThat(overview.activeItems().stream()
                .filter(a -> a.item().item().getId().equals("epic")).findFirst().orElseThrow().progress())
                .isEqualTo(Progress.EMPTY);
    }

    @Test
    void healthIsComputedOncePassingTheForecastsOfTheActiveItems() {
        WorkItem prd = item("prd", "PRD", "ENGINEERING", "DRAFT", null);
        load(prd);

        service.overview(PROJECT);

        ArgumentCaptor<Map<String, Forecast>> forecasts = ArgumentCaptor.forClass(Map.class);
        verify(healthService).current(eq(PROJECT), anyCollection(), forecasts.capture());
        assertThat(forecasts.getValue()).containsOnlyKeys("prd");
    }

    @Test
    void attentionListsSpanTheWholeTreeButSkipFinishedAndPlainTopLevelItems() {
        WorkItem prd = item("prd", "PRD", "ENGINEERING", "DRAFT", null);
        WorkItem blocked = item("blocked", "TASK", "TASK", "TODO", "prd");
        WorkItem stale = item("stale", "TASK", "TASK", "IN_PROGRESS", "prd");
        WorkItem asking = item("asking", "TASK", "TASK", "IN_PROGRESS", "prd");
        WorkItem finishedBlocked = item("finished", "TASK", "TASK", "DONE", "prd");
        WorkItem post = item("post", "POST", "ENGINEERING", "DRAFT", null);
        load(prd, blocked, stale, asking, finishedBlocked, post);
        signals.put("blocked", new Signals(true, List.of(), false, false, false, null));
        signals.put("stale", new Signals(false, List.of(), false, true, false, null));
        signals.put("asking", new Signals(false, List.of(), false, false, true,
                session("asking", WorkItemAgentSession.ACTIVITY_NEEDS_INPUT)));
        signals.put("finished", new Signals(true, List.of(), false, true, false, null));
        // The PRD reports needsInput because a task beneath it asks; only the task is listed.
        signals.put("prd", new Signals(false, List.of(), false, false, true, null));
        signals.put("post", new Signals(true, List.of(), false, false, false, null));

        Overview overview = service.overview(PROJECT);

        assertThat(overview.attention().blocked()).extracting(s -> s.item().getId()).containsExactly("blocked");
        assertThat(overview.attention().stale()).extracting(s -> s.item().getId()).containsExactly("stale");
        assertThat(overview.attention().needsInput()).extracting(s -> s.item().getId()).containsExactly("asking");
    }

    @Test
    void activeItemsAreOrderedByPriorityThenCreation() {
        WorkItem low = item("low", "PRD", "ENGINEERING", "DRAFT", null);
        low.setPriority(3);
        WorkItem none = item("none", "PRD", "ENGINEERING", "DRAFT", null);
        WorkItem urgent = item("urgent", "PRD", "ENGINEERING", "DRAFT", null);
        urgent.setPriority(0);
        load(low, none, urgent);

        assertThat(service.overview(PROJECT).activeItems()).extracting(a -> a.item().item().getId())
                .containsExactly("urgent", "low", "none");
    }
}
