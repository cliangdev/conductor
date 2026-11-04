package com.conductor.repository;

import com.conductor.entity.ActionInvocation;
import com.conductor.entity.ActionInvocationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ActionInvocationRepository extends JpaRepository<ActionInvocation, String> {

    Optional<ActionInvocation> findByIdempotencyKey(String idempotencyKey);

    /**
     * Candidates for a retry sweep: FAILED rows, and PENDING rows, whose last activity
     * ({@code COALESCE(last_attempted_at, created_at)}) is older than {@code cutoff}. A never-attempted
     * row is aged by its creation time, so a row a live {@code invoke} has just inserted (and is still
     * fetching a token for) is NOT a candidate. Excludes {@code "wfstep:"}-prefixed idempotency keys —
     * those belong to workflow-step invocations, which already reported their own terminal StepResult
     * synchronously back to the job orchestrator; re-driving one later would silently re-invoke the
     * connector for a job run that has already finished. No upper bound on attempts here (mirrors
     * {@code WebhookEventRepository#findRetryable}) — the service decides retry vs. dead-letter, and
     * applies the longer per-connector "abandoned" window to PENDING rows.
     */
    @Query("SELECT a FROM ActionInvocation a WHERE a.status IN ("
            + "com.conductor.entity.ActionInvocationStatus.PENDING, com.conductor.entity.ActionInvocationStatus.FAILED) "
            + "AND a.idempotencyKey NOT LIKE 'wfstep:%' "
            + "AND COALESCE(a.lastAttemptedAt, a.createdAt) < :cutoff")
    List<ActionInvocation> findRetryable(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Atomic compare-and-set claim for a sweep retry: succeeds (returns 1) only if the row is still in
     * the state the sweep read ({@code status}, {@code attempts}) and has had no activity since
     * {@code staleBefore}. On success it flips the row to PENDING, bumps {@code attempts} and stamps
     * {@code last_attempted_at}, so a concurrent sweep (another instance) or an inline run sees it as
     * owned. Returns 0 if anyone else got there first.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ActionInvocation a SET a.status = com.conductor.entity.ActionInvocationStatus.PENDING, "
            + "a.attempts = a.attempts + 1, a.lastAttemptedAt = :now "
            + "WHERE a.id = :id AND a.status = :status AND a.attempts = :attempts "
            + "AND COALESCE(a.lastAttemptedAt, a.createdAt) < :staleBefore")
    int claimForRetry(@Param("id") String id,
                      @Param("status") ActionInvocationStatus status,
                      @Param("attempts") int attempts,
                      @Param("staleBefore") OffsetDateTime staleBefore,
                      @Param("now") OffsetDateTime now);
}
