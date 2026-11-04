package com.conductor.creative;

import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guarantee behind "one pooled connection at a time": follow-ups of a committed transaction (releasing a
 * render run, queueing a render, carrying an edit to its Posts) start only once the transaction has ended and
 * its connection is back in the pool. Uses the real transaction manager of the shared test context.
 */
class AfterTransactionTest extends AbstractNoneWebIntegrationTest {

    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void followUpsRunInOrderAfterTheTransactionHasEndedWithNothingBound() {
        List<String> log = new ArrayList<>();

        String result = AfterTransaction.execute(transactionManager, followUps -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            followUps.add(() -> log.add("first, tx active: " + TransactionSynchronizationManager.isActualTransactionActive()
                    + ", synchronization: " + TransactionSynchronizationManager.isSynchronizationActive()));
            followUps.add(() -> {
                // A follow-up may open a transaction of its own: it is the only one this thread holds.
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        log.add("second, own tx active: " + TransactionSynchronizationManager.isActualTransactionActive()));
            });
            log.add("body");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(log).containsExactly("body", "first, tx active: false, synchronization: false",
                "second, own tx active: true");
    }

    @Test
    void aFailingFollowUpNeitherStopsTheOthersNorSurfaces() {
        List<String> log = new ArrayList<>();

        AfterTransaction.execute(transactionManager, followUps -> {
            followUps.add(() -> {
                throw new IllegalStateException("boom");
            });
            followUps.add(() -> log.add("still ran"));
            return null;
        });

        assertThat(log).containsExactly("still ran");
    }

    @Test
    void aBodyThatThrowsRunsNoFollowUps() {
        List<String> log = new ArrayList<>();

        assertThatThrownBy(() -> AfterTransaction.execute(transactionManager, followUps -> {
            followUps.add(() -> log.add("must not run"));
            throw new IllegalArgumentException("rolled back");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(log).isEmpty();
    }

    @Test
    void insideACallersTransactionTheFollowUpsWaitForItsCommit() {
        List<String> log = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            AfterTransaction.execute(transactionManager, followUps -> {
                followUps.add(() -> log.add("follow-up"));
                return null;
            });
            log.add("outer still open");
        });

        assertThat(log).containsExactly("outer still open", "follow-up");
    }
}
