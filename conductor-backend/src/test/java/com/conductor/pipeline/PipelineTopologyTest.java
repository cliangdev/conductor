package com.conductor.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PipelineTopology#EDGES} to the known-correct 5-edge branching DAG (issue #342) -- a
 * regression guard against a well-intentioned but wrong hand-edit of the constant. Deliberately not in
 * {@code PipelineHealthServiceTest} (which mocks every repository {@code getHealth} touches): this
 * constant needs no collaborators at all, and adding it there would leave that test class's shared
 * {@code @BeforeEach} stubbing unused for this one method, tripping Mockito's strict-stubs check.
 */
class PipelineTopologyTest {

    @Test
    void isTheFiveEdgeBranchingDagNotALinearChain() {
        assertThat(PipelineTopology.EDGES).containsExactly(
                new PipelineTopology.Edge("WEBHOOKS", "INBOX"),
                new PipelineTopology.Edge("FEEDS", "DIGESTS"),
                new PipelineTopology.Edge("DIGESTS", "INBOX"),
                new PipelineTopology.Edge("INBOX", "LIBRARIAN_RUNS"),
                new PipelineTopology.Edge("LIBRARIAN_RUNS", "PAGES_WRITTEN"));
    }
}
