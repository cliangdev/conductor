package com.conductor.disposition;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispositionPolicyCacheTest {

    private final DispositionPolicyRepository repository = mock(DispositionPolicyRepository.class);
    private final DispositionPolicyCache cache = new DispositionPolicyCache(repository);

    @AfterEach
    void cleanUpSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

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

    // ---- invalidate deferral inside an active transaction ----

    @Test
    void invalidate_withActiveTransaction_defersDropUntilAfterCommit() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1"))
                .thenReturn(List.of(policy("metrics.digest.**", Disposition.KNOWLEDGE)));
        // Populate the cache before the transaction starts.
        cache.matching("proj-1", "metrics.digest.gsc.weekly");

        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidate("proj-1");

            // Still cached right after invalidate() -- the drop hasn't happened yet.
            assertThat(cache.matching("proj-1", "metrics.digest.gsc.weekly")).hasSize(1);
            verify(repository, times(1)).findByProjectIdAndEnabledTrue("proj-1");

            // Simulate the commit: run the registered synchronizations' afterCommit().
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // Now dropped -- the next read re-hits the repository.
        cache.matching("proj-1", "metrics.digest.gsc.weekly");
        verify(repository, times(2)).findByProjectIdAndEnabledTrue("proj-1");
    }

    @Test
    void invalidate_withActiveTransactionThatRollsBack_neverDropsTheSnapshot() {
        when(repository.findByProjectIdAndEnabledTrue("proj-1"))
                .thenReturn(List.of(policy("metrics.digest.**", Disposition.KNOWLEDGE)));
        cache.matching("proj-1", "metrics.digest.gsc.weekly");

        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidate("proj-1");
            // Rollback: afterCommit() never runs. Only clear the synchronization list, as a real
            // rollback would (no afterCommit callback fires).
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // The write never landed, so the cached snapshot was right all along and is left alone.
        cache.matching("proj-1", "metrics.digest.gsc.weekly");
        verify(repository, times(1)).findByProjectIdAndEnabledTrue("proj-1");
    }
}
