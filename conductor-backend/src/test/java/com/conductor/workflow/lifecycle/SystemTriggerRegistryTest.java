package com.conductor.workflow.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for the classpath-backed {@link SystemTriggerRegistry} (#240 §3). */
class SystemTriggerRegistryTest {

    private final SystemTriggerRegistry registry = new SystemTriggerRegistry(new ObjectMapper());

    @Test
    void registersTheBuiltInTriggers() {
        assertThat(registry.isRegistered("pr_merged")).isTrue();
        assertThat(registry.isRegistered("status_changed")).isTrue();
        assertThat(registry.isRegistered("review_approved")).isTrue();
        assertThat(registry.bypassesReviewGate("review_approved")).isFalse();
    }

    @Test
    void rejectsUnknownTriggers() {
        assertThat(registry.isRegistered("connector_event")).isFalse();
        assertThat(registry.isRegistered("nonexistent")).isFalse();
    }

    @Test
    void prMergedBypassesReviewGateButStatusChangedDoesNot() {
        assertThat(registry.bypassesReviewGate("pr_merged")).isTrue();
        assertThat(registry.bypassesReviewGate("status_changed")).isFalse();
    }

    @Test
    void unknownTriggerDoesNotBypassTheGate() {
        assertThat(registry.bypassesReviewGate("nonexistent")).isFalse();
    }

    @Test
    void exposesAllTriggers() {
        assertThat(registry.all())
                .extracting(SystemTriggerRegistry.SystemTrigger::id)
                .containsExactlyInAnyOrder("pr_merged", "status_changed", "review_approved");
    }
}
