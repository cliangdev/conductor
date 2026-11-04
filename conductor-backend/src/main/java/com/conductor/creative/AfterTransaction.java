package com.conductor.creative;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Work to do once a transaction has finished <em>and let go of its connection</em>.
 *
 * <p>Spring runs {@code afterCommit} callbacks while the committed transaction's connection is still bound to the
 * thread, so anything that opens its own transaction there (as the propagation of a slide-text edit to its Posts,
 * and the queueing of a render, do) holds two pooled connections at once, which can starve a small pool (prod
 * has five). {@link #execute} avoids that: the body runs in a transaction of its own and records what is to be
 * done after it; those follow-ups run only once {@code TransactionTemplate.execute} has returned, so no
 * connection of this thread is held, and each follow-up opens its own short transaction. They run on the request
 * thread, before the response, because prod is scale-to-zero and work left for after the response may never get CPU.
 *
 * <p>A caller that is itself inside a transaction (a test, or a future caller) gets the follow-ups after its
 * commit instead: the best that can be done, with the connection still held.
 */
final class AfterTransaction {

    private static final Logger log = LoggerFactory.getLogger(AfterTransaction.class);

    private AfterTransaction() {
    }

    /** Collects the follow-ups a transactional body asks for. */
    static final class FollowUps {
        private final List<Runnable> tasks = new ArrayList<>();

        void add(Runnable task) {
            tasks.add(task);
        }

        private void runAll() {
            for (Runnable task : tasks) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    // The transaction is committed: a follow-up failing must not surface from the request.
                    log.warn("A follow-up of a committed transaction failed", e);
                }
            }
        }
    }

    static <T> T execute(PlatformTransactionManager transactionManager, Function<FollowUps, T> body) {
        FollowUps followUps = new FollowUps();
        T result = new TransactionTemplate(transactionManager).execute(status -> body.apply(followUps));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // Joined a caller's transaction: only its commit can start the follow-ups.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    followUps.runAll();
                }
            });
        } else {
            followUps.runAll();
        }
        return result;
    }
}
