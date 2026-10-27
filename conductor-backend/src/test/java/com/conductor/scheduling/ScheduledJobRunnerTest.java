package com.conductor.scheduling;

import com.conductor.scheduling.ScheduledJobDefinition.Trigger;
import com.conductor.scheduling.ScheduledJobLeaseRepository.Claim;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledJobRunnerTest {

    private final ScheduledJobRegistry registry = mock(ScheduledJobRegistry.class);
    private final ScheduledJobLeaseRepository leases = mock(ScheduledJobLeaseRepository.class);

    private ScheduledJobDefinition job(Runnable body) {
        ScheduledJobDefinition def = new ScheduledJobDefinition("job-a",
                new Trigger.FixedDelay(Duration.ofSeconds(60), Duration.ZERO),
                Duration.ofMinutes(5), Duration.ofSeconds(30), body);
        when(registry.find("job-a")).thenReturn(Optional.of(def));
        return def;
    }

    private ScheduledJobRunner runner(int slots, boolean enabled) {
        return new ScheduledJobRunner(registry, leases, slots, enabled);
    }

    @Test
    void claimedJobRunsAndReleasesOk() {
        AtomicInteger ran = new AtomicInteger();
        job(ran::incrementAndGet);
        when(leases.claim(eq("job-a"), anyString(), any(), any())).thenReturn(Claim.CLAIMED);

        var result = runner(3, true).run("job-a");

        assertThat(result.outcome()).isEqualTo(TickOutcome.RAN);
        assertThat(ran).hasValue(1);
        verify(leases).release(eq("job-a"), anyString(), eq("OK"), isNull());
    }

    @Test
    void leasedJobIsSkippedWithoutRunningBody() {
        AtomicInteger ran = new AtomicInteger();
        job(ran::incrementAndGet);
        when(leases.claim(eq("job-a"), anyString(), any(), any())).thenReturn(Claim.LEASED);

        assertThat(runner(3, true).run("job-a").outcome()).isEqualTo(TickOutcome.SKIPPED_LEASED);
        assertThat(ran).hasValue(0);
        verify(leases, never()).release(anyString(), anyString(), anyString(), any());
    }

    @Test
    void recentlyStartedJobIsSkipped() {
        job(() -> { });
        when(leases.claim(eq("job-a"), anyString(), any(), any())).thenReturn(Claim.RECENT);

        assertThat(runner(3, true).run("job-a").outcome()).isEqualTo(TickOutcome.SKIPPED_RECENT);
    }

    @Test
    void claimUsesTheJobsTtlAndSpacing() {
        job(() -> { });
        when(leases.claim(any(), any(), any(), any())).thenReturn(Claim.LEASED);

        runner(3, true).run("job-a");

        verify(leases).claim(eq("job-a"), anyString(), eq(Duration.ofMinutes(5)), eq(Duration.ofSeconds(30)));
    }

    @Test
    void failingBodyReleasesLeaseAsFailedAndRethrows() {
        job(() -> { throw new IllegalStateException("boom"); });
        when(leases.claim(eq("job-a"), anyString(), any(), any())).thenReturn(Claim.CLAIMED);

        assertThatThrownBy(() -> runner(3, true).run("job-a"))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
        verify(leases).release(eq("job-a"), anyString(), eq("FAILED"), org.mockito.ArgumentMatchers.contains("boom"));
    }

    @Test
    void unknownJobAndDisabledSchedulingDoNothing() {
        when(registry.find("nope")).thenReturn(Optional.empty());
        assertThat(runner(3, true).run("nope").outcome()).isEqualTo(TickOutcome.UNKNOWN);

        job(() -> { });
        assertThat(runner(3, false).run("job-a").outcome()).isEqualTo(TickOutcome.DISABLED);
        verify(leases, never()).claim(any(), any(), any(), any());
    }

    @Test
    void semaphoreBoundsConcurrentTicksPerInstance() throws Exception {
        CountDownLatch inBody = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        job(() -> {
            ran.incrementAndGet();
            inBody.countDown();
            try {
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        when(leases.claim(eq("job-a"), anyString(), any(), any())).thenReturn(Claim.CLAIMED);
        ScheduledJobRunner runner = runner(1, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> runner.run("job-a"));
            assertThat(inBody.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> runner.run("job-a"));
            Thread.sleep(200);
            assertThat(ran).as("second tick waits for the only slot").hasValue(1);
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(ran).hasValue(2);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }
}
