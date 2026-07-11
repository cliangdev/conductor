package com.conductor.workflow;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeTargetResolverTest {

    private final RuntimeTargetResolver resolver =
            new RuntimeTargetResolver("gcp-proj", "us-central1", "conductor-claude-code");

    @Test
    void resolve_cloudRunReturnsBuiltinTargetAndDefaultImage() {
        Optional<RuntimeTargetResolver.ResolvedRuntime> resolved = resolver.resolve("proj-1", "cloud-run");

        assertThat(resolved).isPresent();
        CloudRunTarget target = resolved.get().target();
        assertThat(target.gcpProjectId()).isEqualTo("gcp-proj");
        assertThat(target.region()).isEqualTo("us-central1");
        assertThat(target.jobName()).isEqualTo("conductor-claude-code");
        assertThat(target.connectionId()).isNull();
        assertThat(resolved.get().image()).isEqualTo(RunnerImage.DEFAULT);
    }

    @Test
    void resolve_selfHostedReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", "self-hosted")).isEmpty();
    }

    @Test
    void resolve_conductorReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", "conductor")).isEmpty();
    }

    @Test
    void resolve_unknownRunsOnReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", "some-custom-target")).isEmpty();
    }

    @Test
    void resolve_nullRunsOnReturnsEmpty() {
        assertThat(resolver.resolve("proj-1", null)).isEmpty();
    }
}
