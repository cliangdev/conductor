package com.conductor.scheduling;

import com.conductor.scheduling.ScheduledJobDefinition.Trigger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Fires every registry job from this instance's own timer, through {@link ScheduledJobRunner}. Active when
 * {@code conductor.scheduling.mode=in-app} (the default); {@code external} leaves triggering to something
 * else. {@code conductor.scheduling.enabled=false} registers nothing, as with {@code SchedulingConfig}.
 */
@Configuration
@ConditionalOnProperty(name = "conductor.scheduling.mode", havingValue = "in-app", matchIfMissing = true)
public class InAppScheduledJobs implements SchedulingConfigurer {

    private final ScheduledJobRegistry registry;
    private final ScheduledJobRunner runner;
    private final boolean schedulingEnabled;

    public InAppScheduledJobs(ScheduledJobRegistry registry, ScheduledJobRunner runner,
                              @Value("${conductor.scheduling.enabled:true}") boolean schedulingEnabled) {
        this.registry = registry;
        this.runner = runner;
        this.schedulingEnabled = schedulingEnabled;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!schedulingEnabled) {
            return;
        }
        for (ScheduledJobDefinition job : registry.jobs()) {
            Tick tick = new Tick(job.name(), runner);
            switch (job.inAppTrigger()) {
                case Trigger.FixedDelay t -> registrar.addFixedDelayTask(
                        new FixedDelayTask(tick, t.delay(), t.initialDelay()));
                case Trigger.Cron t -> registrar.addCronTask(new CronTask(tick, t.expression()));
            }
        }
    }

    private record Tick(String jobName, ScheduledJobRunner runner) implements NamedJob {
        @Override
        public void run() {
            runner.run(jobName);
        }
    }
}
