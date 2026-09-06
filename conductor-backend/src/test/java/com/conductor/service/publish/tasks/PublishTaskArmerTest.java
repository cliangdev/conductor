package com.conductor.service.publish.tasks;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.publish.PublishPlatformRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublishTaskArmer} decides, per row, what the next timed step is and when — the table in
 * docs/publishing.md#firing-on-time — and arms nothing for a row with nothing timed ahead of it.
 */
@ExtendWith(MockitoExtension.class)
class PublishTaskArmerTest {

    @Mock PostPublishTargetRepository targetRepository;
    @Mock PublishTaskScheduler scheduler;
    PublishPlatformRegistry registry = new PublishPlatformRegistry();
    PublishTaskArmer armer;
    OffsetDateTime now;

    @BeforeEach
    void setUp() {
        armer = new PublishTaskArmer(targetRepository, registry, scheduler);
        now = OffsetDateTime.now();
    }

    private static PostPublishTarget target(String id, String platform, PublishLane lane,
                                            PostPublishTargetState state, OffsetDateTime fireTime) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId(id);
        t.setPlatform(platform);
        t.setLane(lane);
        t.setState(state);
        t.setFireTime(fireTime);
        return t;
    }

    @Test
    void pendingAppManaged_dispatchesAtFireTime() {
        OffsetDateTime fire = now.plusHours(3);
        Optional<PublishTask> task = armer.taskFor(target("t", "instagram", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, fire), now);
        assertThat(task).contains(PublishTask.dispatch("t", fire, fire));
    }

    @Test
    void pendingManual_dispatchesAtFireTime_soAHumanIsFlaggedOnTime() {
        OffsetDateTime fire = now.plusHours(3);
        Optional<PublishTask> task = armer.taskFor(target("t", "tiktok", PublishLane.MANUAL,
                PostPublishTargetState.PENDING, fire), now);
        assertThat(task).contains(PublishTask.dispatch("t", fire, fire));
    }

    @Test
    void pendingNative_handsOffWhenThePlatformWindowOpens() {
        Duration facebookMaxLead = registry.require("facebook").window().maxLead();
        assertThat(facebookMaxLead).isNotNull();
        OffsetDateTime fire = now.plus(facebookMaxLead).plusDays(10);

        Optional<PublishTask> task = armer.taskFor(target("t", "facebook", PublishLane.NATIVE,
                PostPublishTargetState.PENDING, fire), now);

        assertThat(task).isPresent();
        assertThat(task.get().kind()).isEqualTo(PublishTaskKind.HANDOFF);
        assertThat(task.get().notBefore()).isEqualTo(fire.minus(facebookMaxLead));
        assertThat(task.get().fireTime()).isEqualTo(fire);
    }

    @Test
    void pendingNative_handsOffNow_whenAlreadyInsideTheWindow() {
        OffsetDateTime fire = now.plusHours(2);
        Optional<PublishTask> task = armer.taskFor(target("t", "facebook", PublishLane.NATIVE,
                PostPublishTargetState.PENDING, fire), now);
        assertThat(task).isPresent();
        assertThat(task.get().kind()).isEqualTo(PublishTaskKind.HANDOFF);
        assertThat(task.get().notBefore()).isEqualTo(now);
    }

    @Test
    void handedOff_confirmsAtFireTime_carryingTheRowsAttemptCount() {
        OffsetDateTime fire = now.plusHours(1);
        PostPublishTarget row = target("t", "youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        row.setAttempts(2);
        assertThat(armer.taskFor(row, now)).contains(PublishTask.confirm("t", fire, fire, 2));
    }

    @Test
    void handedOff_pastFireTime_confirmsNow() {
        OffsetDateTime fire = now.minusMinutes(5);
        PostPublishTarget row = target("t", "youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        assertThat(armer.taskFor(row, now)).contains(PublishTask.confirm("t", fire, now, 0));
    }

    @Test
    void terminalOrUnscheduledRows_getNoTask() {
        OffsetDateTime fire = now.plusHours(1);
        assertThat(armer.taskFor(target("a", "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHED, fire), now)).isEmpty();
        assertThat(armer.taskFor(target("b", "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.FAILED, fire), now)).isEmpty();
        assertThat(armer.taskFor(target("c", "facebook", PublishLane.NATIVE, PostPublishTargetState.REVOKED, fire), now)).isEmpty();
        assertThat(armer.taskFor(target("d", "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHING, fire), now)).isEmpty();
        assertThat(armer.taskFor(target("e", "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, null), now)).isEmpty();
    }

    @Test
    void armPost_schedulesOneTaskPerArmableTarget_afterCommit() {
        WorkItem post = new WorkItem();
        post.setId("post-1");
        OffsetDateTime fire = now.plusHours(1);
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(
                target("ig", "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, fire),
                target("fb", "facebook", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire),
                target("done", "youtube", PublishLane.NATIVE, PostPublishTargetState.PUBLISHED, fire)));

        int armed = armer.armPost(post);

        assertThat(armed).isEqualTo(2);
        ArgumentCaptor<PublishTask> captor = ArgumentCaptor.forClass(PublishTask.class);
        verify(scheduler, org.mockito.Mockito.times(2)).scheduleAfterCommit(captor.capture());
        assertThat(captor.getAllValues()).extracting(PublishTask::targetId, PublishTask::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ig", PublishTaskKind.DISPATCH),
                        org.assertj.core.groups.Tuple.tuple("fb", PublishTaskKind.CONFIRM));
    }

    @Test
    void armPost_withNoPost_armsNothing() {
        assertThat(armer.armPost(null)).isZero();
        verify(scheduler, never()).scheduleAfterCommit(any());
    }
}
