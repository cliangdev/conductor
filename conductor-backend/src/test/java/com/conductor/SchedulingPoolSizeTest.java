package com.conductor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code spring.task.scheduling.pool.size} must be at least the number of distinct {@code @Scheduled}
 * methods, as that property's own comment requires: the pool is shared, so once there are more recurring
 * tasks than threads, one tick that blocks (a lock wait, a call not bounded by a timeout) delays unrelated
 * schedulers. This repo has fixed that class of bug before.
 *
 * <p>It is a drift guard rather than a design assertion. Adding a {@code @Scheduled} method is a one-line
 * change in one file, and the matching pool increment lives in a different file that is easy to forget —
 * which is exactly what happened while COND-23 was in flight: four schedulers were added, only three
 * increments landed, and the resulting 14-against-17 starved
 * {@code KnowledgeIngestSchedulerIntegrationTest} in CI while passing locally.
 */
class SchedulingPoolSizeTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

    @Test
    void schedulingPoolHasAThreadForEveryScheduledMethod() throws IOException {
        List<String> owners = new ArrayList<>();
        int scheduled = 0;
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int count = countOccurrences(Files.readString(file), "@Scheduled");
                if (count > 0) {
                    scheduled += count;
                    owners.add(file.getFileName() + " x" + count);
                }
            }
        }

        assertThat(poolSize())
                .as("spring.task.scheduling.pool.size must cover all %d @Scheduled methods (%s). "
                        + "Add a thread when you add a scheduler.", scheduled, String.join(", ", owners))
                .isGreaterThanOrEqualTo(scheduled);
    }

    private static int poolSize() throws IOException {
        Matcher matcher = Pattern.compile("^spring\\.task\\.scheduling\\.pool\\.size=(\\d+)$", Pattern.MULTILINE)
                .matcher(Files.readString(PROPERTIES));
        assertThat(matcher.find()).as("spring.task.scheduling.pool.size must be declared").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
