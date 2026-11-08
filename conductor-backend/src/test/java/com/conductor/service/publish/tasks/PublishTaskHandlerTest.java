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
    @Mock com.conductor.service.PublishOutcomeService outcomeService;
    @Mock com.conductor.integration.connector.tiktok.TikTokPermalinkFollowUp tikTokFollowUp;
    @Mock com.conductor.integration.connector.tiktok.TikTokPhotoVerdictCheck verdictCheck;
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
                confirmationPoller, scheduler, outcomeService, tikTokFollowUp, verdictCheck, dispatch, handoff,
                confirm);
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

    /**
     * The in-process sweep checked the row after this task was armed. Ending the chain here would leave the
     * row to a sweep that, on Cloud Run, stops the moment the instance idles — which is how a Facebook post
     * sat at HANDED_OFF for good. The task adopts the row's attempt and carries on, timed from the row's own
     * last check so a second stale delivery names the same Cloud Task.
     */
    @Test
    void confirmBehindTheRow_adoptsTheChainFromTheRowsAttempt() {
        OffsetDateTime fire = now.minusSeconds(10);
        PostPublishTarget target = row("youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        target.setAttempts(5);
        target.setUpdatedAt(now.minusSeconds(20));

        handler.handle(PublishTask.confirm("t-1", fire, fire, 4), now);

        verifyNoInteractions(confirmationPoller);
        verify(scheduler).scheduleAfterCommit(
                PublishTask.confirm("t-1", fire, now.minusSeconds(20).plus(PublishTaskHandler.CONFIRM_INTERVAL), 5));
    }

    @Test
    void confirmAheadOfTheRow_isDropped() {
        OffsetDateTime fire = now.minusSeconds(10);
        PostPublishTarget target = row("youtube", PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, fire);
        target.setAttempts(3);

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

    // ---- late TikTok verdict (photo post that failed while TikTok still held its images) -----------

    private static final OffsetDateTime NO_TIME = null;

    private PostPublishTarget failedPhoto() {
        PostPublishTarget t = row("tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.FAILED,
                now.minusHours(2));
        t.setIdempotencyKey("key-1");
        return t;
    }

    private void givenPending(PostPublishTarget t, java.time.Instant initAt, java.time.Instant expiresAt) {
        when(verdictCheck.pending(t)).thenReturn(Optional.of(
                new com.conductor.integration.connector.tiktok.TikTokPhotoVerdictCheck.Pending("pub-1", expiresAt, initAt)));
    }

    private static com.conductor.integration.connector.tiktok.TikTokPhotoVerdictCheck.Reading reading(
            String status, String failReason, String postId, String permalink) {
        return new com.conductor.integration.connector.tiktok.TikTokPhotoVerdictCheck.Reading(
                new com.conductor.integration.connector.tiktok.TikTokClient.PublishStatus(status, postId, permalink,
                        failReason, 0L, null, "log-1"), postId, permalink);
    }

    @Test
    void verdict_whenTikTokPublishedLate_recordsTheSuccessWithItsLink() {
        PostPublishTarget t = failedPhoto();
        givenPending(t, now.minusHours(3).toInstant(), now.plusHours(40).toInstant());
        when(verdictCheck.read(t, "pub-1")).thenReturn(reading("PUBLISH_COMPLETE", null, "77",
                "https://www.tiktok.com/@a/photo/77"));

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 2), now);

        verify(outcomeService).recordLatePublish("t-1", "pub-1", "77", "https://www.tiktok.com/@a/photo/77",
                "photo");
        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void verdict_whenCompleteWithoutALink_handsOverToThePermalinkChain() {
        PostPublishTarget t = failedPhoto();
        givenPending(t, now.minusHours(3).toInstant(), now.plusHours(40).toInstant());
        when(verdictCheck.read(t, "pub-1")).thenReturn(reading("PUBLISH_COMPLETE", null, null, null));

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 0), now);

        // recordLatePublish starts the PERMALINK follow-up itself when no link was given
        verify(outcomeService).recordLatePublish("t-1", "pub-1", null, null, "photo");
    }

    @Test
    void verdict_whenTikTokFailedIt_replacesTheMessageReleasesTheLinksAndStops() {
        PostPublishTarget t = failedPhoto();
        givenPending(t, now.minusHours(3).toInstant(), now.plusHours(40).toInstant());
        when(verdictCheck.read(t, "pub-1")).thenReturn(reading("FAILED", "picture_size_check_failed", null, null));

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 0), now);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(outcomeService).reviseFailureMessage(eq("t-1"), message.capture());
        assertThat(message.getValue()).contains("picture size check failed").contains("safe to retry");
        verify(verdictCheck).releaseLinks(t);
        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void verdict_whileTikTokIsStillProcessing_reArmsOnTheCadence() {
        PostPublishTarget t = failedPhoto();
        givenPending(t, now.minusHours(3).toInstant(), now.plusHours(40).toInstant());
        when(verdictCheck.read(t, "pub-1")).thenReturn(reading("PROCESSING_DOWNLOAD", null, null, null));

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 0), now);

        ArgumentCaptor<PublishTask> next = ArgumentCaptor.forClass(PublishTask.class);
        verify(scheduler).scheduleAfterCommit(next.capture());
        assertThat(next.getValue().kind()).isEqualTo(PublishTaskKind.TIKTOK_VERDICT);
        assertThat(next.getValue().attempt()).isEqualTo(1);
        assertThat(next.getValue().fireTime()).isEqualTo(t.getFireTime());
        assertThat(next.getValue().notBefore()).isEqualTo(now.plusMinutes(30));
        verify(outcomeService, never()).reviseFailureMessage(anyString(), anyString());
    }

    @Test
    void verdict_aFailedReadCountsAsAMissAndKeepsTheChainAlive() {
        PostPublishTarget t = failedPhoto();
        givenPending(t, now.minusHours(3).toInstant(), now.plusHours(40).toInstant());
        when(verdictCheck.read(t, "pub-1")).thenThrow(new IllegalStateException("tiktok 500"));

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 3), now);

        verify(scheduler).scheduleAfterCommit(any());
    }

    @Test
    void verdict_pastTheLastCheck_saysTikTokNeverDownloadedAndARetryIsSafe() {
        PostPublishTarget t = failedPhoto();
        java.time.Instant expiry = now.minusMinutes(70).toInstant();
        givenPending(t, now.minusHours(49).toInstant(), expiry);
        when(verdictCheck.read(t, "pub-1")).thenReturn(reading("PROCESSING_DOWNLOAD", null, null, null));

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 9), now);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(outcomeService).reviseFailureMessage(eq("t-1"), message.capture());
        assertThat(message.getValue()).contains("never downloaded the images").contains("safe to retry");
        verify(scheduler, never()).scheduleAfterCommit(any());
    }

    @Test
    void verdict_forATargetThatWasRetriedOrMoved_isANoOp() {
        // retried: the row is PENDING again under a restamped fire time
        PostPublishTarget t = row("tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, now.plusMinutes(1));
        handler.handle(PublishTask.tiktokVerdict("t-1", now.minusHours(2), now, 1), now);
        // retried and failed again: FAILED, but under a different fire time than the chain was armed for
        t.setState(PostPublishTargetState.FAILED);
        handler.handle(PublishTask.tiktokVerdict("t-1", now.minusHours(2), now, 1), now);
        // published already
        t.setState(PostPublishTargetState.PUBLISHED);
        t.setFireTime(now.minusHours(2));
        handler.handle(PublishTask.tiktokVerdict("t-1", now.minusHours(2), now, 1), now);

        verifyNoInteractions(verdictCheck, scheduler);
        verify(outcomeService, never()).recordLatePublish(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void verdict_whenTheLinksWereReleased_stopsWithoutAskingTikTok() {
        PostPublishTarget t = failedPhoto();
        when(verdictCheck.pending(t)).thenReturn(Optional.empty());

        handler.handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 1), now);

        verify(verdictCheck, never()).read(any(), anyString());
        verifyNoInteractions(scheduler);
    }

    @Test
    void verdict_isIgnored_whenThePublishLaneIsSwitchedOff() {
        PostPublishTarget t = failedPhoto();

        handler(false, true, true).handle(PublishTask.tiktokVerdict("t-1", t.getFireTime(), now, 0), now);

        verifyNoInteractions(verdictCheck, scheduler);
    }

    // ---- permalink follow-up (published without a link) ----------------------------------------

    private PostPublishTarget publishedWithoutLink() {
        PostPublishTarget t = row("tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHED, now.minusMinutes(3));
        t.setResumeCheckpoint(new com.conductor.service.publish.PermalinkFollowUp("p_pub_url~1", "photo").toJson());
        return t;
    }

    @Test
    void permalinkFollowUp_whenTikTokNowHasTheId_recordsTheLinkThroughTheOutcomeService() {
        PostPublishTarget t = publishedWithoutLink();
        when(tikTokFollowUp.check(eq(t), any())).thenReturn(Optional.of(
                new com.conductor.integration.connector.tiktok.TikTokPermalinkFollowUp.Found("77",
                        "https://www.tiktok.com/@a/photo/77")));

        handler.handle(PublishTask.permalink("t-1", t.getFireTime(), now, 1), now);

        verify(outcomeService).recordSuccess("t-1", "77", "https://www.tiktok.com/@a/photo/77");
        verifyNoInteractions(scheduler);
    }

    @Test
    void permalinkFollowUp_whenTheIdIsStillMissing_reArmsOnTheNextBackoffStep() {
        PostPublishTarget t = publishedWithoutLink();
        when(tikTokFollowUp.check(eq(t), any())).thenReturn(Optional.empty());

        handler.handle(PublishTask.permalink("t-1", t.getFireTime(), now, 0), now);

        org.mockito.ArgumentCaptor<PublishTask> next = org.mockito.ArgumentCaptor.forClass(PublishTask.class);
        verify(scheduler).scheduleAfterCommit(next.capture());
        assertThat(next.getValue().kind()).isEqualTo(PublishTaskKind.PERMALINK);
        assertThat(next.getValue().attempt()).isEqualTo(1);
        assertThat(next.getValue().notBefore()).isEqualTo(now.plus(PublishTaskArmer.PERMALINK_BACKOFF.get(1)));
        verify(outcomeService, never()).recordSuccess(anyString(), any(), any());
    }

    @Test
    void permalinkFollowUp_aFailedCheckCountsAsAMissAndKeepsTheChainAlive() {
        PostPublishTarget t = publishedWithoutLink();
        when(tikTokFollowUp.check(eq(t), any())).thenThrow(new IllegalStateException("429"));

        handler.handle(PublishTask.permalink("t-1", t.getFireTime(), now, 0), now);

        verify(scheduler).scheduleAfterCommit(any());
    }

    @Test
    void permalinkFollowUp_stopsAtTheAttemptCap() {
        PostPublishTarget t = publishedWithoutLink();
        when(tikTokFollowUp.check(eq(t), any())).thenReturn(Optional.empty());
        int last = PublishTaskArmer.PERMALINK_BACKOFF.size() - 1;

        handler.handle(PublishTask.permalink("t-1", t.getFireTime(), now, last), now);

        verifyNoInteractions(scheduler);
        verify(outcomeService, never()).recordSuccess(anyString(), any(), any());
    }

    @Test
    void permalinkFollowUp_isANoOpOnceTheRowHasALink() {
        PostPublishTarget t = publishedWithoutLink();
        t.setPermalink("https://www.tiktok.com/@a/photo/1");

        handler.handle(PublishTask.permalink("t-1", t.getFireTime(), now, 0), now);

        verifyNoInteractions(tikTokFollowUp, scheduler, outcomeService);
    }

    @Test
    void permalinkFollowUp_isANoOpWhenTheRowCarriesNoFollowUp() {
        row("tiktok", PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHED, now.minusMinutes(3));

        handler.handle(PublishTask.permalink("t-1", now.minusMinutes(3), now, 0), now);

        verifyNoInteractions(tikTokFollowUp, scheduler, outcomeService);
    }
}
