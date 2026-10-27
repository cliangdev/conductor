package com.conductor.scheduling;

import com.conductor.scheduling.ScheduledJobLeaseRepository.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs one registered job by name: bounded concurrency per instance, a database lease so the job never
 * overlaps itself across instances or triggers, then the body, then release. Exceptions from the body are
 * rethrown after the lease is released.
 */
@Component
public class ScheduledJobRunner {

    public record RunResult(TickOutcome outcome, long durationMs) {}

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);
    private static final String INSTANCE = UUID.randomUUID().toString().substring(0, 8);

    private final ScheduledJobRegistry registry;
    private final ScheduledJobLeaseRepository leases;
    private final Semaphore slots;
    private final boolean schedulingEnabled;
    private final long slotWaitSeconds;

    public ScheduledJobRunner(ScheduledJobRegistry registry, ScheduledJobLeaseRepository leases,
                              @Value("${conductor.scheduling.max-concurrent-ticks:3}") int maxConcurrentTicks,
                              @Value("${conductor.scheduling.enabled:true}") boolean schedulingEnabled,
                              @Value("${conductor.scheduling.slot-wait-seconds:30}") long slotWaitSeconds) {
        this.registry = registry;
        this.leases = leases;
        this.slots = new Semaphore(Math.max(1, maxConcurrentTicks));
        this.schedulingEnabled = schedulingEnabled;
        this.slotWaitSeconds = slotWaitSeconds;
    }

    public RunResult run(String name) {
        long start = System.nanoTime();
        Optional<ScheduledJobDefinition> found = registry.find(name);
        if (found.isEmpty()) {
            return finish(name, TickOutcome.UNKNOWN, start);
        }
        if (!schedulingEnabled) {
            return finish(name, TickOutcome.DISABLED, start);
        }
        ScheduledJobDefinition job = found.get();
        try {
            if (!slots.tryAcquire(slotWaitSeconds, TimeUnit.SECONDS)) {
                return finish(name, TickOutcome.SKIPPED_BUSY, start);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a scheduler slot: " + name, e);
        }
        try {
            String holder = INSTANCE + ":" + UUID.randomUUID();
            Claim claim = leases.claim(name, holder, job.leaseTtl(), job.minSpacing());
            if (claim != Claim.CLAIMED) {
                return finish(name, claim == Claim.LEASED ? TickOutcome.SKIPPED_LEASED : TickOutcome.SKIPPED_RECENT,
                        start);
            }
            try {
                job.body().run();
            } catch (RuntimeException | Error e) {
                release(name, holder, "FAILED", e.toString());
                finish(name, TickOutcome.FAILED, start);
                throw e;
            }
            release(name, holder, "OK", null);
            return finish(name, TickOutcome.RAN, start);
        } finally {
            slots.release();
        }
    }

    private void release(String name, String holder, String status, String error) {
        try {
            leases.release(name, holder, status, error);
        } catch (RuntimeException e) {
            log.warn("Could not release lease for job {}: {}", name, e.toString());
        }
    }

    private RunResult finish(String name, TickOutcome outcome, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        log.atInfo()
                .addKeyValue("job", name)
                .addKeyValue("outcome", outcome)
                .addKeyValue("durationMs", ms)
                .log("scheduler_tick");
        return new RunResult(outcome, ms);
    }
}
