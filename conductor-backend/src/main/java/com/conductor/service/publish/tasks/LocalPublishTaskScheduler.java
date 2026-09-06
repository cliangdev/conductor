package com.conductor.service.publish.tasks;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Local-profile stand-in for {@link CloudTasksPublishTaskScheduler}: no queue exists on a laptop, so a
 * task is held on an in-process timer and handed to {@link PublishTaskHandler} at its {@code notBefore}.
 * Fine for local dev (there is no CPU-throttled Cloud Run to worry about there); never used in a real
 * deployment.
 *
 * <p>Gated by the same flags as the pollers it stands beside ({@code conductor.post-publish.enabled},
 * {@code conductor.native-handoff.enabled}, {@code conductor.native-publish-confirmation.enabled}), which
 * the local profile turns off: nothing should go out from a laptop unless a developer opts in, and then
 * the timer is what lets them watch a scheduled Post fire on the minute.
 *
 * <p>A task runs on its own virtual thread, off the timer thread, so one slow platform call cannot hold
 * the next task back.
 */
@Component
@Profile("local")
public class LocalPublishTaskScheduler implements PublishTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalPublishTaskScheduler.class);

    private final PublishTaskHandler handler;
    private final boolean dispatchEnabled;
    private final boolean handoffEnabled;
    private final boolean confirmEnabled;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "publish-task-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    // @Lazy breaks the PublishTaskHandler <-> PublishTaskScheduler cycle (the handler re-arms through
    // this scheduler), the same way LocalWorkflowJobDispatcher breaks its cycle with the engine.
    public LocalPublishTaskScheduler(@Lazy PublishTaskHandler handler,
                                     @Value("${conductor.post-publish.enabled:true}") boolean dispatchEnabled,
                                     @Value("${conductor.native-handoff.enabled:true}") boolean handoffEnabled,
                                     @Value("${conductor.native-publish-confirmation.enabled:true}")
                                     boolean confirmEnabled) {
        this.handler = handler;
        this.dispatchEnabled = dispatchEnabled;
        this.handoffEnabled = handoffEnabled;
        this.confirmEnabled = confirmEnabled;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        timer.shutdownNow();
        workers.shutdown();
        workers.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Override
    public void schedule(PublishTask task) {
        if (!enabled(task.kind())) {
            log.debug("{} for publish target {} not armed: its lane is switched off on this profile",
                    task.kind(), task.targetId());
            return;
        }
        long delayMs = Math.max(0, Duration.between(OffsetDateTime.now(), task.notBefore()).toMillis());
        timer.schedule(() -> workers.submit(() -> run(task)), delayMs, TimeUnit.MILLISECONDS);
        log.info("Armed {} for publish target {} in {}s (local timer)", task.kind(), task.targetId(),
                delayMs / 1000);
    }

    private void run(PublishTask task) {
        try {
            handler.handle(task);
        } catch (Exception e) {
            log.error("{} for publish target {} failed: {}", task.kind(), task.targetId(), e.getMessage(), e);
        }
    }

    private boolean enabled(PublishTaskKind kind) {
        return switch (kind) {
            case DISPATCH -> dispatchEnabled;
            case HANDOFF -> handoffEnabled;
            case CONFIRM -> confirmEnabled;
        };
    }
}
