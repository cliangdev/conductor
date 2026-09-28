package com.conductor.creative;

import com.conductor.service.StorageService;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Best-effort storage object deletion deferred to {@code afterCommit}, mirroring {@code
 * com.conductor.workflow.SafeSignalPublish}'s shape: a Creative or Creative photo delete's row is the
 * source of truth the moment the transaction commits, and a bucket hiccup cleaning up the now-orphaned
 * storage object must never roll back — or block — the delete itself. Used by {@link CreativeService}
 * (a deleted Creative's render frame objects) and {@link CreativePhotoService} (a deleted photo's own
 * object).
 */
final class AfterCommitStorageCleanup {

    private AfterCommitStorageCleanup() {
    }

    static void deleteAfterCommit(StorageService storageService, List<String> gcsPaths, Logger log) {
        if (gcsPaths.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteSafely(storageService, gcsPaths, log);
                }
            });
        } else {
            deleteSafely(storageService, gcsPaths, log);
        }
    }

    private static void deleteSafely(StorageService storageService, List<String> gcsPaths, Logger log) {
        for (String gcsPath : gcsPaths) {
            try {
                storageService.delete(gcsPath);
            } catch (RuntimeException e) {
                log.warn("Could not delete storage object {}: {}", gcsPath, e.toString());
            }
        }
    }
}
