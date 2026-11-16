package com.conductor.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

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
public final class AfterTransaction {

    private static final Logger log = LoggerFactory.getLogger(AfterTransaction.class);

    /** The follow-ups of the {@link #execute} body running on this thread, if any; always cleared in a finally. */
    private static final ThreadLocal<FollowUps> CURRENT = new ThreadLocal<>();

    private AfterTransaction() {
    }

    /** Collects the follow-ups a transactional body asks for. */
    public static final class FollowUps {
        private final List<Runnable> tasks = new ArrayList<>();

        public void add(Runnable task) {
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

    /**
     * The follow-ups of the body now running on this thread, or null outside {@link #execute}. {@code AfterCommit}
     * uses it to hand its work here instead of registering an {@code afterCommit} synchronization, which would
     * run while the committed transaction still holds its connection.
     */
    public static FollowUps current() {
        return CURRENT.get();
    }

    /** As {@link #execute(PlatformTransactionManager, Function)} for a body that has no follow-ups of its own to add. */
    public static <T> T execute(PlatformTransactionManager transactionManager, Supplier<T> body) {
        return execute(transactionManager, followUps -> body.get());
    }

    public static <T> T execute(PlatformTransactionManager transactionManager, Function<FollowUps, T> body) {
        FollowUps outer = CURRENT.get();
        FollowUps followUps = new FollowUps();
        T result = new TransactionTemplate(transactionManager).execute(status -> {
            FollowUps previous = CURRENT.get();
            CURRENT.set(followUps);
            try {
                return body.apply(followUps);
            } finally {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        });
        if (outer != null) {
            // Nested in another body (it joined that body's transaction): the outer one decides when these run.
            outer.add(followUps::runAll);
            return result;
        }
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
