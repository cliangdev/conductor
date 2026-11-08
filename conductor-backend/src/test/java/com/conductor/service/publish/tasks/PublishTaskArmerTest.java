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

    // ---- late TikTok verdict chain ----

    @Test
    void verdictCadence_is15Min_30Min_thenHourly_andEndsAtLinkExpiryPlus70Min() {
        java.time.Instant expiry = now.plusHours(40).toInstant();
        assertThat(PublishTaskArmer.nextVerdictAt(0, now, expiry)).contains(now.plusMinutes(15));
        assertThat(PublishTaskArmer.nextVerdictAt(1, now, expiry)).contains(now.plusMinutes(30));
        assertThat(PublishTaskArmer.nextVerdictAt(2, now, expiry)).contains(now.plusHours(1));
        assertThat(PublishTaskArmer.nextVerdictAt(7, now, expiry)).contains(now.plusHours(1));

        // the last slot is pulled in to expiry + 70 min rather than overshooting it
        OffsetDateTime nearEnd = OffsetDateTime.ofInstant(expiry.plus(Duration.ofMinutes(40)), now.getOffset());
        assertThat(PublishTaskArmer.nextVerdictAt(5, nearEnd, expiry))
                .contains(OffsetDateTime.ofInstant(expiry.plus(Duration.ofMinutes(70)), now.getOffset()));
        // once that last check has come, the chain ends
        OffsetDateTime atEnd = OffsetDateTime.ofInstant(expiry.plus(Duration.ofMinutes(70)), now.getOffset());
        assertThat(PublishTaskArmer.nextVerdictAt(6, atEnd, expiry)).isEmpty();
        assertThat(PublishTaskArmer.nextVerdictAt(6, atEnd.plusMinutes(5), expiry)).isEmpty();
    }

    @Test
    void armTikTokVerdict_schedulesTheFirstCheckAgainstTheFireTimeSnapshot() {
        OffsetDateTime fire = now.minusHours(1);
        PostPublishTarget t = target("t", "tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.FAILED, fire);

        assertThat(armer.armTikTokVerdict(t, now.plusHours(40).toInstant())).isTrue();

        org.mockito.ArgumentCaptor<PublishTask> task = org.mockito.ArgumentCaptor.forClass(PublishTask.class);
        org.mockito.Mockito.verify(scheduler).scheduleAfterCommit(task.capture());
        assertThat(task.getValue().kind()).isEqualTo(PublishTaskKind.TIKTOK_VERDICT);
        assertThat(task.getValue().fireTime()).isEqualTo(fire);
        assertThat(task.getValue().attempt()).isZero();
        assertThat(task.getValue().notBefore()).isBetween(now.plusMinutes(14), now.plusMinutes(16));
    }

    @Test
    void armTikTokVerdict_armsNothingOnceTheChainWindowIsOver() {
        PostPublishTarget t = target("t", "tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.FAILED, now);

        assertThat(armer.armTikTokVerdict(t, now.minusHours(2).toInstant())).isFalse();

        org.mockito.Mockito.verifyNoInteractions(scheduler);
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
    void armPost_reReadsRowsTheCallersContextHoldsStale_soTheTaskCarriesTheRestampedFireTime() {
        // The row in the caller's context still says the old fire time; the database (via refresh) says the new one.
        WorkItem post = new WorkItem();
        post.setId("post-1");
        OffsetDateTime oldFire = now.plusMinutes(5);
        OffsetDateTime newFire = now.plusHours(2);
        PostPublishTarget stale = target("ig", "instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, oldFire);
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(stale));
        jakarta.persistence.EntityManager em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        when(em.contains(stale)).thenReturn(true);
        when(em.isJoinedToTransaction()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> { stale.setFireTime(newFire); return null; }).when(em).refresh(stale);
        armer.entityManager = em;

        armer.armPost(post);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(em);
        inOrder.verify(em).flush();
        inOrder.verify(em).refresh(stale);
        verify(scheduler).scheduleAfterCommit(PublishTask.dispatch("ig", newFire, newFire));
    }

    @Test
    void armPost_withNoPost_armsNothing() {
        assertThat(armer.armPost(null)).isZero();
        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void publishedWithoutALink_armsAPermalinkFollowUpAtTheFirstBackoffStep() {
        PostPublishTarget t = target("t", "tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHED,
                now.minusMinutes(1));

        armer.armPermalinkFollowUp(t);

        ArgumentCaptor<PublishTask> armed = ArgumentCaptor.forClass(PublishTask.class);
        verify(scheduler).scheduleAfterCommit(armed.capture());
        assertThat(armed.getValue().kind()).isEqualTo(PublishTaskKind.PERMALINK);
        assertThat(armed.getValue().attempt()).isZero();
        assertThat(armed.getValue().notBefore()).isAfter(now.plus(PublishTaskArmer.PERMALINK_BACKOFF.get(0)).minusSeconds(1));
    }

    @Test
    void permalinkBackoff_coversAboutAnHour() {
        assertThat(PublishTaskArmer.PERMALINK_BACKOFF.stream().mapToLong(Duration::toMinutes).sum())
                .isBetween(50L, 75L);
    }
}
