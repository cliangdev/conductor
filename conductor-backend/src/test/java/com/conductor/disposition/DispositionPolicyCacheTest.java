package com.conductor.disposition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispositionPolicyCacheTest {

    private final DispositionPolicyRepository repository = mock(DispositionPolicyRepository.class);
    private final DispositionPolicyCache cache = new DispositionPolicyCache(repository);

    private DispositionPolicy policy(String signalType, Disposition disposition) {
        DispositionPolicy p = new DispositionPolicy();
        p.setProjectId("proj-1");
        p.setSignalType(signalType);
        p.setDisposition(disposition);
        p.setEnabled(true);
        return p;
    }

    @Test
    void emptyTable_returnsNoMatches() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1")).thenReturn(List.of());

        assertThat(cache.matching("proj-1", "metrics.digest.gsc.weekly")).isEmpty();
    }

    @Test
    void globMatchesAgainstSignalType() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1"))
                .thenReturn(List.of(policy("metrics.digest.**", Disposition.KNOWLEDGE)));

        assertThat(cache.matching("proj-1", "metrics.digest.gsc.weekly")).hasSize(1);
        assertThat(cache.matching("proj-1", "github.pull_request")).isEmpty();
    }

    @Test
    void secondReadForSameProject_doesNotHitTheRepositoryAgain() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1")).thenReturn(List.of());

        cache.matching("proj-1", "a.b");
        cache.matching("proj-1", "c.d");

        verify(repository, times(1)).findByProjectIdAndEnabledTrue("proj-1");
    }

    @Test
    void invalidate_forcesAReReadOnNextMatch() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1")).thenReturn(List.of());

        cache.matching("proj-1", "a.b");
        cache.invalidate("proj-1");
        cache.matching("proj-1", "a.b");

        verify(repository, times(2)).findByProjectIdAndEnabledTrue("proj-1");
    }

    @Test
    void repositoryThrows_failsOpenWithNoMatches() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1")).thenThrow(new RuntimeException("db down"));

        assertThat(cache.matching("proj-1", "a.b")).isEmpty();
    }
}
