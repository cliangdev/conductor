package com.conductor.service.publish.tasks;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.NativeHandoffService;
import com.conductor.service.NativePublishConfirmationPoller;
import com.conductor.service.NativePublishConfirmationPoller.ConfirmOutcome;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.workflow.PostPublishScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PublishTaskHandler} owns the three arrival checks — stale, early, owned elsewhere — and routes
 * everything else to the poller that does the work. Each check is what makes a task safe to deliver twice,
 * late, or after the Post changed, so each gets a case.
 */
@ExtendWith(MockitoExtension.class)
class PublishTaskHandlerTest {

    @Mock PostPublishTargetRepository targetRepository;
    @Mock PostPublishScheduler postPublishScheduler;
    @Mock NativeHandoffService nativeHandoffService;
    @Mock NativePublishConfirmationPoller confirmationPoller;
    @Mock PublishTaskScheduler scheduler;
    PublishPlatformRegistry registry = new PublishPlatformRegistry();
    PublishTaskHandler handler;
    OffsetDateTime now;

    @BeforeEach
    void setUp() {
        handler = handler(true, true, true);
        now = OffsetDateTime.now().withNano(0);
    }

    private PublishTaskHandler handler(boolean dispatch, boolean handoff, boolean confirm) {
        return new PublishTaskHandler(targetRepository, registry, postPublishScheduler, nativeHandoffService,
                confirmationPoller, scheduler, dispatch, handoff, confirm);
    }

    private PostPublishTarget row(String platform, PublishLane lane, PostPublishTargetState state, OffsetDateTime fire) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId("t-1");
        t.setPlatform(platform);
        t.setLane(lane);
        t.setState(state);
        t.setFireTime(fire);
        when(targetRepository.findById("t-1")).thenReturn(Optional.of(t));
        return t;
    }

    // ---- stale ----------------------------------------------------------------------------------

    @Test
    void staleTask_whoseSnapshotNoLongerMatchesTheRow_isDropped() {
        OffsetDateTime rescheduled = now.plusHours(4);
        row("instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, rescheduled);

        handler.handle(PublishTask.dispatch("t-1", now.minusMinutes(1), now.minusMinutes(1)), now);

        verifyNoInteractions(postPublishScheduler, scheduler);
    }

    @Test
    void taskForAVanishedRow_isDropped() {
        when(targetRepository.findById("t-1")).thenReturn(Optional.empty());
        handler.handle(PublishTask.dispatch("t-1", now, now), now);
        verifyNoInteractions(postPublishScheduler, scheduler);
    }

    // ---- dispatch -------------------------------------------------------------------------------

    @Test
    void dueDispatch_firesTheTarget() {
        OffsetDateTime fire = now.minusSeconds(2);
        row("instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, fire);

        handler.handle(PublishTask.dispatch("t-1", fire, fire), now);

        verify(postPublishScheduler).fireTarget("t-1", now);
        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void earlyDispatch_isReArmedForTheFireTime_notFired() {
        // the task was created at Cloud Tasks' 30-day cap and arrived before the row is due
        OffsetDateTime fire = now.plusDays(20);
        row("instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, fire);

        handler.handle(PublishTask.dispatch("t-1", fire, now), now);

        verify(postPublishScheduler, never()).fireTarget(anyString(), any());
        verify(scheduler).scheduleAfterCommit(PublishTask.dispatch("t-1", fire, fire));
    }

    @Test
    void dispatchForARowThatAlreadyMoved_isANoOp() {
        OffsetDateTime fire = now.minusSeconds(2);
        row("instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHED, fire);

        handler.handle(PublishTask.dispatch("t-1", fire, fire), now);

        verifyNoInteractions(postPublishScheduler, scheduler);
    }

    @Test
    void dispatch_isIgnored_whenThePublishLaneIsSwitchedOff() {
        OffsetDateTime fire = now.minusSeconds(2);
        row("instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, fire);

        handler(false, true, true).handle(PublishTask.dispatch("t-1", fire, fire), now);

        verifyNoInteractions(postPublishScheduler, scheduler);
    }

    // ---- hand-off -------------------------------------------------------------------------------

    @Test
    void handoffInsideTheWindow_handsOff_thenArmsConfirmationAtTheFireTime() {
        OffsetDateTime fire = now.plusHours(2);
        PostPublishTarget target = row("facebook", PublishLane.NATIVE, PostPublishTargetState.PENDING, fire);
        // the hand-off succeeds: the row is HANDED_OFF when the handler looks again
        when(targetRepository.findById("t-1")).thenReturn(Optional.of(target), Optional.of(handedOff(target)));

        handler.handle(PublishTask.handoff("t-1", fire, now), now);

        verify(nativeHandoffService).handoffNow("t-1", now);
        verify(scheduler).scheduleAfterCommit(PublishTask.confirm("t-1", fire, fire, 0));
    }

    @Test
    void handoffThatDidNotTake_armsNoConfirmation() {
        OffsetDateTime fire = now.plusHours(2);
        row("facebook", PublishLane.NATIVE, PostPublishTargetState.PENDING, fire);

        handler.handle(PublishTask.handoff("t-1", fire, now), now);

        verify(nativeHandoffService).handoffNow("t-1", now);
        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void handoffBeforeThePlatformWindowOpens_isReArmedForWhenItDoes() {
        Duration maxLead = registry.require("facebook").window().maxLead();
        OffsetDateTime fire = now.plus(maxLead).plusDays(5);
        row("facebook", PublishLane.NATIVE, PostPublishTargetState.PENDING, fire);

        handler.handle(PublishTask.handoff("t-1", fire, now), now);

        verify(nativeHandoffService, never()).handoffNow(anyString(), any());
        verify(scheduler).scheduleAfterCommit(PublishTask.handoff("t-1", fire, fire.minus(maxLead)));
    }

    @Test
    void handoffForARowAlreadyHandedOff_ensuresAConfirmationChain() {
        // scheduled entry hands off immediately in its own transaction; the armer, still seeing PENDING,
        // armed a HANDOFF anyway — it must not strand the row without a confirmation
        OffsetDateTime fire = now.plusHours(2);
        PostPublishTarget target = row("facebook", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        target.setAttempts(1);

        handler.handle(PublishTask.handoff("t-1", fire, now), now);

        verify(nativeHandoffService, never()).handoffNow(anyString(), any());
        verify(scheduler).scheduleAfterCommit(PublishTask.confirm("t-1", fire, fire, 1));
    }

    @Test
    void handoffForARowThatFailedOrWasRevoked_isANoOp() {
        OffsetDateTime fire = now.plusHours(2);
        row("facebook", PublishLane.NATIVE, PostPublishTargetState.REVOKED, fire);

        handler.handle(PublishTask.handoff("t-1", fire, now), now);

        verifyNoInteractions(nativeHandoffService, scheduler);
    }

    @Test
    void handoffForANonNativeRow_isANoOp() {
        OffsetDateTime fire = now.plusHours(1);
        row("instagram", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, fire);

        handler.handle(PublishTask.handoff("t-1", fire, now), now);

        verifyNoInteractions(nativeHandoffService, scheduler);
    }

    // ---- confirm --------------------------------------------------------------------------------

    @Test
    void confirmNotYetLive_reArmsAMinuteLater_withTheNextAttemptNumber() {
        OffsetDateTime fire = now.minusSeconds(10);
        PostPublishTarget target = row("youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        target.setAttempts(3);
        when(confirmationPoller.confirmNow("t-1", now)).thenReturn(ConfirmOutcome.RETRY_LATER);

        handler.handle(PublishTask.confirm("t-1", fire, fire, 3), now);

        verify(scheduler).scheduleAfterCommit(
                PublishTask.confirm("t-1", fire, now.plus(PublishTaskHandler.CONFIRM_INTERVAL), 4));
    }

    @Test
    void confirmSettled_endsTheChain() {
        OffsetDateTime fire = now.minusSeconds(10);
        row("youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        when(confirmationPoller.confirmNow("t-1", now)).thenReturn(ConfirmOutcome.SETTLED);

        handler.handle(PublishTask.confirm("t-1", fire, fire, 0), now);

        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void confirmOwnedByAnotherChain_isDropped_soChainsNeverFork() {
        OffsetDateTime fire = now.minusSeconds(10);
        PostPublishTarget target = row("youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        target.setAttempts(5);

        handler.handle(PublishTask.confirm("t-1", fire, fire, 4), now);

        verifyNoInteractions(confirmationPoller, scheduler);
    }

    @Test
    void earlyConfirm_isReArmedForTheFireTime() {
        OffsetDateTime fire = now.plusDays(15);
        row("youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);

        handler.handle(PublishTask.confirm("t-1", fire, now, 0), now);

        verifyNoInteractions(confirmationPoller);
        ArgumentCaptor<PublishTask> captor = ArgumentCaptor.forClass(PublishTask.class);
        verify(scheduler).scheduleAfterCommit(captor.capture());
        assertThat(captor.getValue()).isEqualTo(PublishTask.confirm("t-1", fire, fire, 0));
    }

    @Test
    void confirmForARowNoLongerHandedOff_isANoOp() {
        OffsetDateTime fire = now.minusSeconds(10);
        row("youtube", PublishLane.NATIVE, PostPublishTargetState.PUBLISHED, fire);

        handler.handle(PublishTask.confirm("t-1", fire, fire, 0), now);

        verifyNoInteractions(confirmationPoller, scheduler);
    }

    private static PostPublishTarget handedOff(PostPublishTarget from) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId(from.getId());
        t.setPlatform(from.getPlatform());
        t.setLane(from.getLane());
        t.setState(PostPublishTargetState.HANDED_OFF);
        t.setFireTime(from.getFireTime());
        return t;
    }
}
