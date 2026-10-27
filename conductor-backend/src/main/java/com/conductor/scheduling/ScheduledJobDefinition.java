package com.conductor.scheduling;

import java.time.Duration;
import java.util.Objects;

/**
 * One timed job: a stable name, how the in-app timer fires it, how long a claim holds it, how soon after
 * the previous start it may run again, and the tick method itself.
 */
public record ScheduledJobDefinition(String name, Trigger inAppTrigger, Duration leaseTtl, Duration minSpacing,
                                     Runnable body) {

    public ScheduledJobDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(inAppTrigger, "inAppTrigger");
        Objects.requireNonNull(leaseTtl, "leaseTtl");
        Objects.requireNonNull(minSpacing, "minSpacing");
        Objects.requireNonNull(body, "body");
    }

    /** How the in-app timer fires a job. */
    public sealed interface Trigger {
        record FixedDelay(Duration delay, Duration initialDelay) implements Trigger {}

        record Cron(String expression) implements Trigger {}
    }
}
