package com.conductor.signal;

import com.conductor.tx.AfterTransaction;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work once the surrounding transaction has committed. {@link SignalBus#publish} delivers to subscribers
 * on the caller's thread inside the caller's transaction, so a subscriber that does something the outside world
 * can see (send an email, start a workflow run) defers it here: a comment that rolls back must not email anyone
 * or start an agent.
 *
 * <p>Three cases, in order:
 * <ol>
 *   <li>Inside {@link AfterTransaction#execute}: the work joins that call's follow-ups, which run after the
 *       transaction has committed <em>and released its connection</em>, so the work (an HTTP call, a transaction
 *       of its own) never holds a second pooled connection.</li>
 *   <li>Inside some other transaction: it runs in {@code afterCommit}. The committed transaction's resources are
 *       still bound to the thread then, so work that needs a transaction must open a {@code REQUIRES_NEW} one
 *       rather than join the finished one.</li>
 *   <li>No transaction: it runs immediately.</li>
 * </ol>
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable work) {
        AfterTransaction.FollowUps followUps = AfterTransaction.current();
        if (followUps != null) {
            followUps.add(work);
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
            return;
        }
        work.run();
    }
}
