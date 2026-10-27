package com.conductor.scheduling;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scripts/setup-cloud-scheduler.sh holds the one table of Cloud Scheduler jobs. Its names must equal the
 * registry's exactly, or a job would never be triggered (missing) or would 404 forever (stale).
 */
class CloudSchedulerScriptTest {

    private static final Pattern ROW = Pattern.compile("^\\s*\"([a-z0-9-]+)\\|[^\"]+\"\\s*$");

    @Test
    void scriptJobTable_matchesRegistryNamesExactly() throws Exception {
        Path script = Path.of("..", "scripts", "setup-cloud-scheduler.sh");
        if (!Files.exists(script)) {
            script = Path.of("scripts", "setup-cloud-scheduler.sh");
        }
        List<String> scriptNames = Files.readAllLines(script).stream()
                .map(ROW::matcher).filter(Matcher::matches).map(m -> m.group(1)).toList();

        Object[] args = Arrays.stream(ScheduledJobRegistry.class.getConstructors()[0].getParameterTypes())
                .map(t -> Mockito.mock(t)).toArray();
        ScheduledJobRegistry registry = (ScheduledJobRegistry) ScheduledJobRegistry.class.getConstructors()[0]
                .newInstance(args);
        List<String> registryNames = registry.jobs().stream().map(ScheduledJobDefinition::name).toList();

        assertThat(scriptNames).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(registryNames);
    }
}
