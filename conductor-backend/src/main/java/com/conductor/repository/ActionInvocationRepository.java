package com.conductor.repository;

import com.conductor.entity.ActionInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ActionInvocationRepository extends JpaRepository<ActionInvocation, String> {

    Optional<ActionInvocation> findByIdempotencyKey(String idempotencyKey);

    /**
     * PENDING/FAILED rows due for a retry sweep. Excludes {@code "wfstep:"}-prefixed idempotency keys —
     * those belong to workflow-step invocations, which already reported their own terminal StepResult
     * (SUCCESS/FAILED) synchronously back to the job orchestrator; re-driving one later would silently
     * re-invoke the connector for a job run that has already finished. Only ad-hoc (non-step) action
     * invocations are eligible for the background sweep. No upper bound on attempts here (mirrors
     * {@code WebhookEventRepository#findRetryable}) — the scheduler itself decides retry vs. dead-letter.
     */
    @Query("SELECT a FROM ActionInvocation a WHERE a.status IN ("
            + "com.conductor.entity.ActionInvocationStatus.PENDING, com.conductor.entity.ActionInvocationStatus.FAILED) "
            + "AND a.idempotencyKey NOT LIKE 'wfstep:%' "
            + "AND (a.lastAttemptedAt IS NULL OR a.lastAttemptedAt < :cutoff)")
    List<ActionInvocation> findRetryable(@Param("cutoff") OffsetDateTime cutoff);
}
