package com.conductor.scheduling;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler endpoint takes the job name as a free path string validated against the registry (no
 * generated enum to drift), constrained by the pattern in openapi-internal.yaml. Every registered name must
 * satisfy that pattern or Cloud Scheduler could never address it.
 */
class ScheduledJobNamesTest {

    private static final Pattern PATH_PATTERN = Pattern.compile("^[a-z0-9-]{1,100}$");

    @Test
    void everyRegisteredName_isAddressableAndUnique() throws Exception {
        Object[] args = java.util.Arrays.stream(ScheduledJobRegistry.class.getConstructors()[0].getParameterTypes())
                .map(t -> Mockito.mock(t)).toArray();
        ScheduledJobRegistry registry = (ScheduledJobRegistry) ScheduledJobRegistry.class.getConstructors()[0]
                .newInstance(args);
        List<String> names = registry.jobs().stream().map(ScheduledJobDefinition::name).toList();
        assertThat(names).isNotEmpty().doesNotHaveDuplicates().allMatch(n -> PATH_PATTERN.matcher(n).matches());
    }
}
