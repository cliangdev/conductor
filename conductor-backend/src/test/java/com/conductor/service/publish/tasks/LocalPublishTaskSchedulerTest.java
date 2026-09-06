package com.conductor.service.publish.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The local timer must deliver at notBefore on a worker thread, and must stay silent for a lane that is off. */
@ExtendWith(MockitoExtension.class)
class LocalPublishTaskSchedulerTest {

    @Mock PublishTaskHandler handler;
    LocalPublishTaskScheduler scheduler;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void schedule_handsTaskToHandler_onceNotBeforeArrives() throws InterruptedException {
        scheduler = new LocalPublishTaskScheduler(handler, true, true, true);
        CountDownLatch delivered = new CountDownLatch(1);
        doAnswer(invocation -> { delivered.countDown(); return null; }).when(handler).handle(any(PublishTask.class));
        OffsetDateTime fire = OffsetDateTime.now().plusNanos(200_000_000);
        PublishTask task = PublishTask.dispatch("t-1", fire, fire);

        scheduler.schedule(task);

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        verify(handler).handle(task);
    }

    @Test
    void schedule_ignoresTask_whenItsLaneIsSwitchedOff() throws InterruptedException {
        scheduler = new LocalPublishTaskScheduler(handler, false, true, true);
        OffsetDateTime now = OffsetDateTime.now();

        scheduler.schedule(PublishTask.dispatch("t-1", now, now));
        Thread.sleep(300);

        verify(handler, never()).handle(any(PublishTask.class));
    }
}
