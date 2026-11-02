package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemCriterion;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkItemCriterionRepository;
import com.conductor.repository.WorkItemRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkItemCriteriaServiceTest {

    private static final String PROJECT = "proj-1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-01T12:00:00Z");

    @Mock private WorkItemRepository workItemRepository;
    @Mock private WorkItemCriterionRepository criterionRepository;

    private WorkItemCriteriaService service;
    private final List<WorkItemCriterion> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new WorkItemCriteriaService(workItemRepository, criterionRepository,
                Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC));
        Project project = new Project();
        project.setId(PROJECT);
        WorkItem item = new WorkItem();
        item.setId("item-1");
        item.setProject(project);
        when(workItemRepository.findById("item-1")).thenReturn(Optional.of(item));
        when(criterionRepository.findByWorkItemIdOrderByPositionAscCreatedAtAsc("item-1"))
                .thenAnswer(inv -> List.copyOf(stored));
        when(criterionRepository.saveAll(any())).thenAnswer(inv -> {
            List<WorkItemCriterion> rows = new ArrayList<>();
            ((Iterable<WorkItemCriterion>) inv.getArgument(0)).forEach(rows::add);
            rows.forEach(r -> {
                if (r.getId() == null) {
                    r.setId("c-" + (stored.size() + rows.indexOf(r)));
                }
            });
            stored.clear();
            stored.addAll(rows);
            return rows;
        });
        when(criterionRepository.save(any(WorkItemCriterion.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static WorkItemCriteriaService.CriterionInput in(String text, String kind, String ref) {
        return new WorkItemCriteriaService.CriterionInput(text, kind, ref);
    }

    @Test
    void replaceAllAssignsPositionsInOrderAndDefaultsKindToAuto() {
        List<WorkItemCriterion> rows = service.replaceAll(PROJECT, "item-1", List.of(
                in("First", null, "AC-P0-1.1"), in("Second", "manual", null), in("Third", "AUTO", null)));

        assertThat(rows).extracting(WorkItemCriterion::getPosition).containsExactly(0, 1, 2);
        assertThat(rows).extracting(WorkItemCriterion::getKind).containsExactly("auto", "manual", "auto");
        assertThat(rows.get(0).getExternalRef()).isEqualTo("AC-P0-1.1");
        assertThat(rows).noneMatch(WorkItemCriterion::isMet);
    }

    @Test
    void replaceAllKeepsProgressOnACriterionSentAgainAndDropsTheRest() {
        WorkItemCriterion kept = existing("c-a", "Keep me", "AC-1", 0);
        kept.setMet(true);
        kept.setEvidence("tests pass");
        WorkItemCriterion dropped = existing("c-b", "Drop me", null, 1);
        stored.addAll(List.of(kept, dropped));

        List<WorkItemCriterion> rows = service.replaceAll(PROJECT, "item-1", List.of(
                in("New one", null, null), in("Keep me, reworded", null, "AC-1")));

        assertThat(rows).hasSize(2);
        WorkItemCriterion again = rows.get(1);
        assertThat(again).isSameAs(kept);
        assertThat(again.isMet()).isTrue();
        assertThat(again.getEvidence()).isEqualTo("tests pass");
        assertThat(again.getText()).isEqualTo("Keep me, reworded");
        assertThat(again.getPosition()).isEqualTo(1);
        org.mockito.Mockito.verify(criterionRepository).deleteAll(List.of(dropped));
    }

    @Test
    void replaceAllRefusesBlankTextAndUnknownKind() {
        assertThatThrownBy(() -> service.replaceAll(PROJECT, "item-1", List.of(in("  ", null, null))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.replaceAll(PROJECT, "item-1", List.of(in("x", "maybe", null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("kind");
    }

    @Test
    void anItemFromAnotherProjectIs404() {
        assertThatThrownBy(() -> service.replaceAll("other", "item-1", List.of()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void markingMetStampsTimeAndWhoAndReturnsTotals() {
        WorkItemCriterion c1 = existing("c1", "One", null, 0);
        WorkItemCriterion c2 = existing("c2", "Two", null, 1);
        stored.addAll(List.of(c1, c2));
        when(criterionRepository.findById("c1")).thenReturn(Optional.of(c1));

        var result = service.patch(PROJECT, "item-1", "c1", null, null, true, "CI green",
                ProjectActor.agent("claude-code"));

        assertThat(result.criterion().isMet()).isTrue();
        assertThat(result.criterion().getMetAt()).isEqualTo(NOW);
        assertThat(result.criterion().getMetByLabel()).isEqualTo("claude-code");
        assertThat(result.criterion().getEvidence()).isEqualTo("CI green");
        assertThat(result.criteriaMet()).isEqualTo(1);
        assertThat(result.criteriaTotal()).isEqualTo(2);
    }

    @Test
    void markingUnmetClearsTheStamp() {
        WorkItemCriterion c1 = existing("c1", "One", null, 0);
        c1.setMet(true);
        c1.setMetAt(NOW.minusDays(1));
        c1.setMetByLabel("someone");
        stored.add(c1);
        when(criterionRepository.findById("c1")).thenReturn(Optional.of(c1));

        var result = service.patch(PROJECT, "item-1", "c1", null, null, false, null,
                ProjectActor.agent("claude-code"));

        assertThat(result.criterion().isMet()).isFalse();
        assertThat(result.criterion().getMetAt()).isNull();
        assertThat(result.criterion().getMetByLabel()).isNull();
        assertThat(result.criteriaMet()).isZero();
    }

    @Test
    void markingMetTwiceKeepsTheOriginalStamp() {
        WorkItemCriterion c1 = existing("c1", "One", null, 0);
        c1.setMet(true);
        c1.setMetAt(NOW.minusDays(2));
        c1.setMetByLabel("first");
        stored.add(c1);
        when(criterionRepository.findById("c1")).thenReturn(Optional.of(c1));

        service.patch(PROJECT, "item-1", "c1", null, null, true, null, ProjectActor.agent("second"));

        assertThat(c1.getMetAt()).isEqualTo(NOW.minusDays(2));
        assertThat(c1.getMetByLabel()).isEqualTo("first");
    }

    @Test
    void aCriterionOfAnotherItemIs404() {
        WorkItemCriterion foreign = existing("cx", "Elsewhere", null, 0);
        foreign.setWorkItemId("item-2");
        when(criterionRepository.findById("cx")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.patch(PROJECT, "item-1", "cx", null, null, true, null, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private static WorkItemCriterion existing(String id, String text, String ref, int position) {
        WorkItemCriterion c = new WorkItemCriterion();
        c.setId(id);
        c.setProjectId(PROJECT);
        c.setWorkItemId("item-1");
        c.setText(text);
        c.setExternalRef(ref);
        c.setPosition(position);
        return c;
    }
}
