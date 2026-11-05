package com.conductor.service;

import com.conductor.service.ProgressRollup.CriteriaCount;
import com.conductor.service.ProgressRollup.Node;
import com.conductor.service.ProgressRollup.Progress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressRollupTest {

    private static Node node(String id, String parent, boolean terminal) {
        return new Node(id, parent, terminal);
    }

    @Test
    void aRootWithNoChildrenHasNothingToRollUp() {
        Progress p = ProgressRollup.of("prd", List.of(node("prd", null, false)), Map.of());

        assertThat(p).isEqualTo(Progress.EMPTY);
    }

    @Test
    void countsDirectChildrenDoneOverTotal() {
        Progress p = ProgressRollup.of("prd", List.of(
                node("prd", null, false),
                node("t1", "prd", true),
                node("t2", "prd", false),
                node("t3", "prd", true)), Map.of());

        assertThat(p.total()).isEqualTo(3);
        assertThat(p.done()).isEqualTo(2);
        assertThat(p.percent()).isEqualTo(67);
    }

    @Test
    void anEpicIsItsChildrenNotAUnitOfWorkOfItsOwn() {
        // prd -> epic -> (t1 done, t2 open); prd -> t3 done. The epic itself must not count, finished or not.
        Progress p = ProgressRollup.of("prd", List.of(
                node("prd", null, false),
                node("epic", "prd", true),
                node("t1", "epic", true),
                node("t2", "epic", false),
                node("t3", "prd", true)), Map.of());

        assertThat(p.total()).isEqualTo(3);
        assertThat(p.done()).isEqualTo(2);
    }

    @Test
    void theRootIsNeverCountedEvenWhenTerminal() {
        Progress p = ProgressRollup.of("prd", List.of(
                node("prd", null, true),
                node("t1", "prd", false)), Map.of());

        assertThat(p.total()).isEqualTo(1);
        assertThat(p.done()).isZero();
        assertThat(p.percent()).isZero();
    }

    @Test
    void everyLeafDoneIsOneHundredPercent() {
        Progress p = ProgressRollup.of("prd", List.of(
                node("prd", null, false),
                node("t1", "prd", true),
                node("t2", "prd", true)), Map.of());

        assertThat(p.percent()).isEqualTo(100);
    }

    @Test
    void sumsCriteriaAcrossLeavesOnly() {
        Progress p = ProgressRollup.of("prd", List.of(
                node("prd", null, false),
                node("epic", "prd", false),
                node("t1", "epic", false),
                node("t2", "prd", false)), Map.of(
                "prd", new CriteriaCount(9, 9),
                "epic", new CriteriaCount(9, 9),
                "t1", new CriteriaCount(1, 3),
                "t2", new CriteriaCount(2, 2)));

        assertThat(p.criteriaTotal()).isEqualTo(5);
        assertThat(p.criteriaMet()).isEqualTo(3);
    }

    @Test
    void itemsWithNoCriteriaContributeNone() {
        Progress p = ProgressRollup.of("prd", List.of(
                node("prd", null, false),
                node("t1", "prd", false)), Map.of());

        assertThat(p.criteriaTotal()).isZero();
        assertThat(p.criteriaMet()).isZero();
    }
}
