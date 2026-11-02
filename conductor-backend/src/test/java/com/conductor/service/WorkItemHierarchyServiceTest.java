package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.service.WorkItemHierarchyService.ParentTransition;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkItemHierarchyServiceTest {

    private static final String PROJECT = "proj-1";

    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemWorkflowService workItemWorkflowService;
    @Mock private com.conductor.repository.WorkItemCriterionRepository criterionRepository;

    private WorkItemHierarchyService service;
    private Statechart taskChart;
    private Statechart engineeringChart;
    private Project project;
    private final Map<String, WorkItem> store = new HashMap<>();
    private int nextSequence = 1;

    @BeforeEach
    void setUp() throws Exception {
        taskChart = chart("/schema/examples/task.workflow.json");
        engineeringChart = chart("/schema/examples/engineering.workflow.json");
        project = new Project();
        project.setId(PROJECT);

        service = new WorkItemHierarchyService(workItemRepository, workItemWorkflowService,
                criterionRepository);

        when(workItemRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        when(workItemWorkflowService.resolveFor(eq(PROJECT), anyString(), any()))
                .thenAnswer(inv -> "TASK".equals(inv.<String>getArgument(1)) ? taskChart : engineeringChart);
        // The real service advances the item along the edge declaring the trigger and returns that edge.
        when(workItemWorkflowService.applySystemTransition(eq(PROJECT), any(WorkItem.class), eq("children_done")))
                .thenAnswer(inv -> {
                    WorkItem parent = inv.getArgument(1);
                    Optional<StatechartTransition> edge =
                            taskChart.triggeredTransitionFrom(parent.getCurrentStatus(), "children_done");
                    edge.ifPresent(e -> parent.setCurrentStatus(e.to()));
                    return edge;
                });
        when(workItemRepository.findChildren(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(w -> inv.getArgument(0).equals(w.getParentWorkItemId())).toList());
        when(workItemRepository.findSubtreeIds(anyString())).thenAnswer(inv -> subtreeIds(inv.getArgument(0)));
        when(workItemRepository.findSubtreeIdsFor(any())).thenAnswer(inv -> {
            List<Object[]> rows = new ArrayList<>();
            for (Object root : (Iterable<?>) inv.getArgument(0)) {
                if (store.containsKey((String) root)) {
                    subtreeIds((String) root).forEach(id -> rows.add(new Object[] {root, id}));
                }
            }
            return rows;
        });
        when(workItemRepository.findAllById(any())).thenAnswer(inv -> {
            List<WorkItem> found = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                found.add(store.get((String) id));
            }
            return found;
        });
    }

    private Statechart chart(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        }
    }

    private WorkItem item(String id, String workflow, String status, String parentId) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProject(project);
        w.setWorkflow(workflow);
        w.setWorkflowVersion(1);
        w.setType("TASK".equals(workflow) ? "TASK" : "PRD");
        w.setCurrentStatus(status);
        w.setParentWorkItemId(parentId);
        w.setSequenceNumber(nextSequence++);
        store.put(id, w);
        return w;
    }

    /** Parents before children, like the recursive CTE. */
    private List<String> subtreeIds(String rootId) {
        List<String> ids = new ArrayList<>();
        ids.add(rootId);
        for (int i = 0; i < ids.size(); i++) {
            String current = ids.get(i);
            store.values().stream().filter(w -> current.equals(w.getParentWorkItemId()))
                    .forEach(w -> ids.add(w.getId()));
        }
        return ids;
    }

    // --- children_done roll-up ---

    @Test
    void lastChildFinishingCompletesATaskParent() {
        WorkItem epic = item("epic", "TASK", "IN_PROGRESS", null);
        item("t1", "TASK", "DONE", "epic");
        WorkItem t2 = item("t2", "TASK", "DONE", "epic");

        List<ParentTransition> moved = service.onChildStatusChanged(PROJECT, t2);

        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).parent()).isSameAs(epic);
        assertThat(moved.get(0).fromStatus()).isEqualTo("IN_PROGRESS");
        assertThat(moved.get(0).toStatus()).isEqualTo("DONE");
        assertThat(epic.getCurrentStatus()).isEqualTo("DONE");
        verify(workItemRepository).save(epic);
    }

    @Test
    void cancelledSiblingsCountAsFinishedToo() {
        WorkItem epic = item("epic", "TASK", "TODO", null);
        item("t1", "TASK", "CANCELLED", "epic");
        WorkItem t2 = item("t2", "TASK", "DONE", "epic");

        service.onChildStatusChanged(PROJECT, t2);

        assertThat(epic.getCurrentStatus()).isEqualTo("DONE");
    }

    @Test
    void parentStaysOpenWhileAnyChildIsUnfinished() {
        WorkItem epic = item("epic", "TASK", "IN_PROGRESS", null);
        item("t1", "TASK", "IN_PROGRESS", "epic");
        WorkItem t2 = item("t2", "TASK", "DONE", "epic");

        assertThat(service.onChildStatusChanged(PROJECT, t2)).isEmpty();

        assertThat(epic.getCurrentStatus()).isEqualTo("IN_PROGRESS");
        verify(workItemWorkflowService, never()).applySystemTransition(any(), any(), anyString());
    }

    @Test
    void childrenDoneFiresOnlyForTaskParents() {
        WorkItem prd = item("prd", "ENGINEERING", "IN_PROGRESS", null);
        WorkItem t1 = item("t1", "TASK", "DONE", "prd");

        assertThat(service.onChildStatusChanged(PROJECT, t1)).isEmpty();

        assertThat(prd.getCurrentStatus()).isEqualTo("IN_PROGRESS");
        verify(workItemWorkflowService, never()).applySystemTransition(any(), any(), anyString());
        verify(workItemRepository, never()).save(any());
    }

    @Test
    void completionRollsUpThroughNestedEpicsButStopsAtThePrd() {
        WorkItem prd = item("prd", "ENGINEERING", "IN_PROGRESS", null);
        WorkItem outer = item("outer", "TASK", "IN_PROGRESS", "prd");
        WorkItem inner = item("inner", "TASK", "IN_PROGRESS", "outer");
        WorkItem leaf = item("leaf", "TASK", "DONE", "inner");

        List<ParentTransition> moved = service.onChildStatusChanged(PROJECT, leaf);

        assertThat(moved).extracting(m -> m.parent().getId()).containsExactly("inner", "outer");
        assertThat(inner.getCurrentStatus()).isEqualTo("DONE");
        assertThat(outer.getCurrentStatus()).isEqualTo("DONE");
        assertThat(prd.getCurrentStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void rollUpStopsAtTheFirstAncestorWithAnOpenChild() {
        WorkItem outer = item("outer", "TASK", "IN_PROGRESS", null);
        WorkItem inner = item("inner", "TASK", "IN_PROGRESS", "outer");
        item("sibling", "TASK", "TODO", "outer");
        WorkItem leaf = item("leaf", "TASK", "DONE", "inner");

        List<ParentTransition> moved = service.onChildStatusChanged(PROJECT, leaf);

        assertThat(moved).extracting(m -> m.parent().getId()).containsExactly("inner");
        assertThat(outer.getCurrentStatus()).isEqualTo("IN_PROGRESS");
        assertThat(inner.getCurrentStatus()).isEqualTo("DONE");
    }

    @Test
    void aFinishedParentIsNeverReopenedOrTouchedAgain() {
        WorkItem epic = item("epic", "TASK", "DONE", null);
        WorkItem t1 = item("t1", "TASK", "DONE", "epic");

        assertThat(service.onChildStatusChanged(PROJECT, t1)).isEmpty();

        assertThat(epic.getCurrentStatus()).isEqualTo("DONE");
        verify(workItemRepository, never()).save(any());
    }

    @Test
    void aChildBeingReopenedNeverReopensItsParent() {
        WorkItem epic = item("epic", "TASK", "DONE", null);
        WorkItem t1 = item("t1", "TASK", "TODO", "epic");

        assertThat(service.onChildStatusChanged(PROJECT, t1)).isEmpty();

        assertThat(epic.getCurrentStatus()).isEqualTo("DONE");
    }

    @Test
    void aGateRefusingTheParentMoveLeavesItWhereItWasWithoutFailing() {
        WorkItem epic = item("epic", "TASK", "IN_PROGRESS", null);
        WorkItem t1 = item("t1", "TASK", "DONE", "epic");
        when(workItemWorkflowService.applySystemTransition(eq(PROJECT), eq(epic), eq("children_done")))
                .thenThrow(new UnprocessableEntityException("fields missing"));

        assertThat(service.onChildStatusChanged(PROJECT, t1)).isEmpty();

        assertThat(epic.getCurrentStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void anItemWithNoParentHasNothingToRollUp() {
        WorkItem top = item("top", "TASK", "DONE", null);

        assertThat(service.onChildStatusChanged(PROJECT, top)).isEmpty();

        verify(workItemRepository, never()).findChildren(anyString());
    }

    // --- parentage ---

    @Test
    void setParentPointsTheItemAtTheParent() {
        item("prd", "ENGINEERING", "DRAFT", null);
        WorkItem task = item("t1", "TASK", "TODO", null);

        service.setParent(PROJECT, task, "prd");

        assertThat(task.getParentWorkItemId()).isEqualTo("prd");
    }

    @Test
    void aBlankParentDetachesTheItem() {
        item("prd", "ENGINEERING", "DRAFT", null);
        WorkItem task = item("t1", "TASK", "TODO", "prd");

        service.setParent(PROJECT, task, "");

        assertThat(task.getParentWorkItemId()).isNull();
    }

    @Test
    void anItemCannotBeItsOwnParent() {
        WorkItem task = item("t1", "TASK", "TODO", null);

        assertThatThrownBy(() -> service.setParent(PROJECT, task, "t1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("own parent");
    }

    @Test
    void aParentFromAnotherProjectIsRefused() {
        Project other = new Project();
        other.setId("proj-2");
        WorkItem foreign = item("foreign", "ENGINEERING", "DRAFT", null);
        foreign.setProject(other);
        WorkItem task = item("t1", "TASK", "TODO", null);

        assertThatThrownBy(() -> service.setParent(PROJECT, task, "foreign"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not found in this project");
        assertThat(task.getParentWorkItemId()).isNull();
    }

    @Test
    void aMissingParentIsRefused() {
        WorkItem task = item("t1", "TASK", "TODO", null);

        assertThatThrownBy(() -> service.setParent(PROJECT, task, "nope"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void reparentingUnderOwnDescendantIsRefusedAsACycle() {
        WorkItem a = item("a", "TASK", "TODO", null);
        item("b", "TASK", "TODO", "a");
        item("c", "TASK", "TODO", "b");

        assertThatThrownBy(() -> service.setParent(PROJECT, a, "c"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("cycle");
        assertThat(a.getParentWorkItemId()).isNull();
    }

    @Test
    void reparentingUnderOwnDirectChildIsRefusedAsACycle() {
        WorkItem a = item("a", "TASK", "TODO", null);
        item("b", "TASK", "TODO", "a");

        assertThatThrownBy(() -> service.setParent(PROJECT, a, "b")).isInstanceOf(ConflictException.class);
    }

    @Test
    void aBranchMayBeFiveLevelsDeepButNotSix() {
        item("l1", "TASK", "TODO", null);
        item("l2", "TASK", "TODO", "l1");
        item("l3", "TASK", "TODO", "l2");
        item("l4", "TASK", "TODO", "l3");
        WorkItem fifth = item("l5", "TASK", "TODO", null);
        WorkItem sixth = item("l6", "TASK", "TODO", null);

        service.setParent(PROJECT, fifth, "l4");
        assertThat(fifth.getParentWorkItemId()).isEqualTo("l4");

        assertThatThrownBy(() -> service.setParent(PROJECT, sixth, "l5"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5 levels");
    }

    @Test
    void reparentingCountsTheMovedItemsOwnSubtreeTowardsTheDepthLimit() {
        item("l1", "TASK", "TODO", null);
        item("l2", "TASK", "TODO", "l1");
        item("l3", "TASK", "TODO", "l2");
        // A branch two levels tall...
        WorkItem top = item("x1", "TASK", "TODO", null);
        item("x2", "TASK", "TODO", "x1");
        item("x3", "TASK", "TODO", "x2");

        // ...under l3 would put x3 at level 6.
        assertThatThrownBy(() -> service.setParent(PROJECT, top, "l3"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5 levels");
        // Under l2 it fits exactly (x3 at level 5).
        service.setParent(PROJECT, top, "l2");
        assertThat(top.getParentWorkItemId()).isEqualTo("l2");
    }

    @Test
    void validatingAParentForANewItemChecksProjectAndDepthWithoutAnItem() {
        item("l1", "TASK", "TODO", null);
        item("l2", "TASK", "TODO", "l1");
        item("l3", "TASK", "TODO", "l2");
        item("l4", "TASK", "TODO", "l3");
        item("l5", "TASK", "TODO", "l4");

        assertThat(service.validateParent(PROJECT, null, "l4").getId()).isEqualTo("l4");
        assertThatThrownBy(() -> service.validateParent(PROJECT, null, "l5"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5 levels");
    }

    // --- progress ---

    @Test
    void progressCountsLeavesAndUsesEachItemsOwnStatechart() {
        item("prd", "ENGINEERING", "IN_PROGRESS", null);
        item("epic", "TASK", "TODO", "prd");
        item("t1", "TASK", "DONE", "epic");
        item("t2", "TASK", "TODO", "epic");
        item("t3", "TASK", "CANCELLED", "prd");

        Progress p = service.progress(PROJECT, "prd");

        assertThat(p.total()).isEqualTo(3);
        assertThat(p.done()).isEqualTo(2);
        assertThat(p.percent()).isEqualTo(67);
        assertThat(p.criteriaTotal()).isZero();
    }

    @Test
    void progressForManyRootsReadsTheSubtreesAndCriteriaOnce() {
        item("prd1", "ENGINEERING", "IN_PROGRESS", null);
        item("a", "TASK", "DONE", "prd1");
        item("b", "TASK", "TODO", "prd1");
        item("prd2", "ENGINEERING", "IN_PROGRESS", null);
        item("c", "TASK", "DONE", "prd2");
        item("solo", "ENGINEERING", "DRAFT", null);

        Map<String, Progress> byRoot = service.progressFor(PROJECT, List.of("prd1", "prd2", "solo", "ghost"));

        assertThat(byRoot.get("prd1").percent()).isEqualTo(50);
        assertThat(byRoot.get("prd2").percent()).isEqualTo(100);
        assertThat(byRoot.get("solo")).isEqualTo(Progress.EMPTY);
        assertThat(byRoot.get("ghost")).isEqualTo(Progress.EMPTY);
        verify(workItemRepository, times(1)).findSubtreeIdsFor(any());
        verify(workItemRepository, never()).findSubtreeIds(anyString());
        verify(criterionRepository, times(1)).countsByWorkItem(any());
    }

    @Test
    void progressOfAnItemWithoutChildrenIsEmpty() {
        item("solo", "ENGINEERING", "DRAFT", null);

        assertThat(service.progress(PROJECT, "solo")).isEqualTo(Progress.EMPTY);
    }
}
