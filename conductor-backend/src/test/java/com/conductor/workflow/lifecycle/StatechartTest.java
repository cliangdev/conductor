package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class StatechartTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Statechart engineering() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            JsonNode def = mapper.readTree(in);
            return Statechart.parse(def);
        }
    }

    @Test
    void parsesCoreMetadata() throws Exception {
        Statechart sc = engineering();
        assertThat(sc.slug()).isEqualTo("ENGINEERING");
        assertThat(sc.area()).isEqualTo("ENGINEERING");
        assertThat(sc.noun()).isEqualTo("Issue");
        assertThat(sc.types()).containsExactly("PRD", "FEATURE_REQUEST", "BUG_REPORT");
        assertThat(sc.assetTypes()).containsExactly("github_pr");
    }

    @Test
    void hasSingleInitialAndTwoTerminals() throws Exception {
        Statechart sc = engineering();
        assertThat(sc.initialStatus()).get().extracting(StatechartStatus::id).isEqualTo("DRAFT");
        assertThat(sc.statuses().stream().filter(StatechartStatus::terminal).map(StatechartStatus::id))
                .containsExactlyInAnyOrder("DONE", "CLOSED");
        assertThat(sc.isTerminal("DONE")).isTrue();
        assertThat(sc.isTerminal("CLOSED")).isTrue();
        assertThat(sc.isTerminal("DRAFT")).isFalse();
    }

    /**
     * The fidelity bar: the built-in ENGINEERING statechart must reproduce today's hardcoded
     * WorkItemService.VALID_TRANSITIONS edge set exactly (incl. CLOSED reachable from all non-terminals and the
     * IN_REVIEW -> DRAFT back-edge). This is what makes AC-P0-1.1 (no regression) achievable.
     */
    @Test
    void edgeSetMatchesHardcodedValidTransitions() throws Exception {
        Map<String, Set<String>> expected = new TreeMap<>();
        expected.put("DRAFT", new TreeSet<>(Set.of("IN_REVIEW", "CLOSED")));
        expected.put("IN_REVIEW", new TreeSet<>(Set.of("READY_FOR_DEVELOPMENT", "DRAFT", "CLOSED")));
        expected.put("READY_FOR_DEVELOPMENT", new TreeSet<>(Set.of("IN_PROGRESS", "CLOSED")));
        expected.put("IN_PROGRESS", new TreeSet<>(Set.of("CODE_REVIEW", "CLOSED")));
        expected.put("CODE_REVIEW", new TreeSet<>(Set.of("DONE", "CLOSED")));

        Statechart sc = engineering();
        Map<String, Set<String>> actual = new TreeMap<>();
        for (StatechartTransition t : sc.transitions()) {
            actual.computeIfAbsent(t.from(), k -> new TreeSet<>()).add(t.to());
        }
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void transitionLookups() throws Exception {
        Statechart sc = engineering();
        assertThat(sc.transition("DRAFT", "IN_REVIEW")).isPresent();
        assertThat(sc.transition("DRAFT", "DONE")).isEmpty();
        List<StatechartTransition> fromInReview = sc.transitionsFrom("IN_REVIEW");
        assertThat(fromInReview).extracting(StatechartTransition::to)
                .containsExactlyInAnyOrder("READY_FOR_DEVELOPMENT", "DRAFT", "CLOSED");
    }

    @Test
    void engineeringMergeIsReviewGated() throws Exception {
        Statechart sc = engineering();
        // CODE_REVIEW -> DONE is the review gate (COND-18 E3 / P0-6). The PR-merge bypass stays ungated.
        StatechartTransition merge = sc.transition("CODE_REVIEW", "DONE").orElseThrow();
        assertThat(merge.requiresReview()).isTrue();
        assertThat(merge.reviewOutcomes()).contains("approve", "request_changes");
        // The CLOSED edge from CODE_REVIEW remains ungated.
        assertThat(sc.transition("CODE_REVIEW", "CLOSED")).get()
                .extracting(StatechartTransition::requiresReview).isEqualTo(false);
    }

    @Test
    void engineeringBindsImplementSkillOnStartWork() throws Exception {
        Statechart sc = engineering();
        StatechartTransition startWork = sc.transition("READY_FOR_DEVELOPMENT", "IN_PROGRESS").orElseThrow();
        assertThat(startWork.steps()).hasSize(1);
        StatechartStep step = startWork.steps().get(0);
        assertThat(step.isSkill()).isTrue();
        assertThat(step.skill()).isEqualTo("conductor:implement");
        assertThat(step.mode()).isEqualTo("BLOCKING");
    }
}
