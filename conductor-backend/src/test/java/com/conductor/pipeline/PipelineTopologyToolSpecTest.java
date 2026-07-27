package com.conductor.pipeline;

import com.conductor.integration.IngestSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CI tripwire for {@link PipelineTopology}'s dormant {@code FEEDS -> INBOX} edge (issue #342).
 * {@code FeedPullService.recordOutcome} has a real code path where a non-metric feed's items bypass
 * {@code ConnectorFeedDigest} entirely -- unreachable today only because every shipped connector's
 * {@code ingest[]} entries are metric feeds. This test parses every tool-spec on the classpath (not a
 * hardcoded filename list, unlike {@code IntegrationToolSpecIngestTest} -- a new connector's tool-spec
 * is covered automatically, no test update needed) and fails loudly, by name, the moment that
 * assumption stops holding -- see {@link PipelineTopology}'s javadoc for what to do when it does.
 *
 * <p>Reads directly off {@code src/main/resources/connectors/tool-specs} on disk (Surefire's working
 * directory is the module base dir) rather than via {@code Class#getResource} -- a classpath resource
 * lookup for a directory resolves to whichever physical directory wins the classpath race
 * ({@code target/test-classes} vs. {@code target/classes}), which would silently scan test fixtures
 * under {@code src/test/resources/connectors/tool-specs} (e.g. {@code fixture-window-fetch-only.json},
 * a deliberately non-metric fixture for {@code IngestMode} tests) instead of, or in addition to, the
 * real shipped connectors this test must cover.
 */
class PipelineTopologyToolSpecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyShippedIngestEntryIsAMetricFeed() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path file : toolSpecFiles()) {
            IntegrationToolSpec spec = mapper.readValue(file.toFile(), IntegrationToolSpec.class);
            for (IngestSpec ingest : spec.ingest()) {
                if (!ingest.isMetricFeed()) {
                    violations.add(file.getFileName() + ":" + ingest.id());
                }
            }
        }

        assertThat(violations)
                .as("Non-metric ingest[] entries found: %s. FeedPullService.recordOutcome routes these "
                        + "straight to the knowledge inbox (FEEDS -> INBOX), bypassing DIGESTS -- a real "
                        + "edge PipelineTopology.EDGES does not model yet because it was previously "
                        + "unreachable (see its javadoc). Add `new PipelineTopology.Edge(\"FEEDS\", "
                        + "\"INBOX\")` there, correct PipelineHealthService's class javadoc, and update "
                        + "docs/knowledge.md's \"Live health\" section -- then relax this assertion for "
                        + "the entry(ies) named above.", violations)
                .isEmpty();
    }

    private static List<Path> toolSpecFiles() throws Exception {
        Path dir = Paths.get("src/main/resources/connectors/tool-specs");
        assertThat(Files.isDirectory(dir))
                .as("expected %s to exist (module base dir must be Surefire's working directory)", dir.toAbsolutePath())
                .isTrue();
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
