package com.conductor.signal;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs work once the surrounding transaction has committed. {@link SignalBus#publish} delivers to subscribers
 * on the caller's thread inside the caller's transaction, so a subscriber that does something the outside world
 * can see (send an email, start a workflow run) defers it here: a comment that rolls back must not email anyone
 * or start an agent. With no transaction active the work runs immediately.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable work) {
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
