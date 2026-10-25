package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.NotificationRollup;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.NotificationRollupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRollupServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONFIG_ID = "cfg-1";
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1/t";

    @Mock private NotificationRollupRepository repository;
    @Mock private NotificationGroupConfigRepository configRepository;
    @Mock private DiscordProvider discord;

    private NotificationRollupService service;
    private NotificationGroupConfig config;

    @BeforeEach
    void setUp() {
        service = new NotificationRollupService(repository, configRepository, discord);
        config = new NotificationGroupConfig();
        config.setId(CONFIG_ID);
        config.setProjectId(PROJECT_ID);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl(WEBHOOK);
        config.setEnabledEventTypes(new java.util.HashSet<>(java.util.List.of(
                "WORK_ITEM_STATUS_CHANGED", "REVIEW_SUBMITTED", "REVIEWER_ASSIGNED")));
        lenient().when(repository.lockChannel(CONFIG_ID)).thenReturn(Optional.of(CONFIG_ID));
        lenient().when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config));
    }

    private static DiscordProvider.PostResult posted(String id) {
        return DiscordProvider.PostResult.posted(id);
    }

    private static DiscordProvider.PostResult post(DiscordProvider.PostOutcome outcome) {
        return DiscordProvider.PostResult.of(outcome);
    }

    private RollupResult result;

    /** True when the roll-up dealt with every event itself (nothing for the caller to send plainly). */
    private boolean rollup(NotificationMessage event) {
        return rollup(List.of(event));
    }

    private boolean rollup(List<NotificationMessage> events) {
        result = service.rollup(CONFIG_ID, events);
        return result.fallbackToPlain().isEmpty();
    }

    private NotificationMessage status(String itemId, String toStatus, String label) {
        return status(itemId, toStatus, label, Map.of());
    }

    private NotificationMessage status(String itemId, String toStatus, String label, Map<String, String> extra) {
        Map<String, String> m = new HashMap<>(extra);
        m.put("workItemId", itemId);
        m.put("workItemTitle", "Title " + itemId);
        m.put("noun", "Post");
        m.put("displayId", "RE-" + itemId);
        m.put("toStatus", toStatus);
        m.put("toStatusLabel", label);
        m.put("toCategory", "in_progress");
        return NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, m);
    }

    private NotificationMessage review(String itemId, String verdict) {
        return NotificationMessage.of(EventType.REVIEW_SUBMITTED, PROJECT_ID, Map.of(
                "workItemId", itemId, "workItemTitle", "Title " + itemId, "noun", "Post",
                "displayId", "RE-" + itemId, "verdict", verdict, "reviewerName", "Bryan",
                "status", "IN_REVIEW", "statusLabel", "In Review"));
    }

    private NotificationRollup row(OffsetDateTime updatedAt, RollupLine... lines) {
        NotificationRollup r = new NotificationRollup();
        r.setConfigId(CONFIG_ID);
        r.setMessageId("msg-1");
        r.setStartedAt(updatedAt);
        r.setUpdatedAt(updatedAt);
        Map<String, RollupLine> map = new HashMap<>();
        for (RollupLine l : lines) map.put(l.workItemId(), l);
        r.setLines(map);
        return r;
    }

    private RollupLine line(String id, long seq, String status, String label, String note) {
        return new RollupLine(id, "RE-" + id, "Title " + id, "Post", status, label, "in_progress", "l", note, seq);
    }

    @Test
    void firstEventPostsAndRecordsTheMessageId() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m-9"));

        assertThat(rollup(status("1", "IN_REVIEW", "In Review"))).isTrue();

        ArgumentCaptor<NotificationRollup> saved = ArgumentCaptor.forClass(NotificationRollup.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMessageId()).isEqualTo("m-9");
        assertThat(saved.getValue().getLines()).containsOnlyKeys("1");
    }

    @Test
    void eventInsideTheWindowEditsTheExistingMessage() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(2), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(DiscordProvider.EditOutcome.OK);

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).postReturningId(anyString(), anyString());
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
        assertThat(r.getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void eachModeBypassesTheRollup() {
        config.setDeliveryMode(DeliveryMode.EACH);

        assertThat(rollup(status("1", "IN_REVIEW", "In Review"))).isFalse();
        assertThat(NotificationRollupService.appliesTo(config, status("1", "IN_REVIEW", "In Review"))).isFalse();

        verify(discord, never()).postReturningId(anyString(), anyString());
        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void theChannelsOwnWindowDecidesWhetherTheMessageIsStillCurrent() {
        config.setGroupWindowMinutes(60);
        // 30 minutes old: outside the 15-minute default, inside this channel's hour
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(30), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(DiscordProvider.EditOutcome.OK);

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).postReturningId(anyString(), anyString());
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
    }

    @Test
    void aShortChannelWindowPostsFreshWhereTheDefaultWouldHaveEdited() {
        config.setGroupWindowMinutes(5);
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(10), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("msg-2"));

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-2");
    }

    @Test
    void eventAfterTheWindowPostsANewMessageStartingFromThatEventAlone() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(30), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("msg-2"));

        assertThat(rollup(status("2", "SCHEDULED", "Scheduled"))).isTrue();

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-2");
        assertThat(r.getLines()).containsOnlyKeys("2");
    }

    @Test
    void sameItemsEventsCollapseToOneLineHoldingTheLatestState() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(status("1", "APPROVED", "Approved"));

        assertThat(r.getLines()).hasSize(1);
        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("Approved");
        // one item: rendered as today's single message
        verify(discord).editMessage(WEBHOOK, "msg-1", "SINGLE");
    }

    @Test
    void verdictNoteSurvivesAStatusChangeInTheSameBatch() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.editMessage(anyString(), anyString(), any())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(List.of(review("1", "APPROVED"), status("1", "SCHEDULED", "Scheduled")));

        RollupLine l = r.getLines().get("1");
        assertThat(l.statusLabel()).isEqualTo("Scheduled");
        assertThat(l.verdictNote()).isEqualTo("approved by Bryan");
    }

    @Test
    void aStatusChangeInALaterBatchClearsTheStaleVerdictNote() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1),
                line("1", 0, "IN_REVIEW", "In Review", "approved by Bryan"));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), any())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(status("1", "PUBLISHED", "Published"));

        RollupLine l = r.getLines().get("1");
        assertThat(l.statusLabel()).isEqualTo("Published");
        assertThat(l.verdictNote()).isNull();
        // no stale note, so the single message is today's plain format, not the combined one
        verify(discord, never()).buildSinglePayload(any());
        verify(discord).editMessage(WEBHOOK, "msg-1", "SINGLE");
    }

    @Test
    void aReviewerAssignmentReplacesAStaleNoteWithAReviewerNote() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1),
                line("1", 0, "IN_REVIEW", "In Review", "approved by Bryan"));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), any())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(NotificationMessage.of(EventType.REVIEWER_ASSIGNED, PROJECT_ID, Map.of(
                "workItemId", "1", "workItemTitle", "Title 1", "reviewerName", "Bob")));

        assertThat(r.getLines().get("1").verdictNote()).isEqualTo("reviewer: Bob");
    }

    @Test
    void aReviewerNoteIsStaleToo_AStatusChangeInALaterBatchClearsIt() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1),
                line("1", 0, "IN_REVIEW", "In Review", "reviewer: Bob"));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), any())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(status("1", "APPROVED", "Approved"));

        assertThat(r.getLines().get("1").verdictNote()).isNull();
    }

    @Test
    void reviewerAssignedOnlyAddsTheItemAndDoesNotTouchExistingState() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "SCHEDULED", "Scheduled", "approved by Bryan"));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(NotificationMessage.of(EventType.REVIEWER_ASSIGNED, PROJECT_ID, Map.of(
                "workItemId", "1", "workItemTitle", "Title 1", "status", "IN_REVIEW", "statusLabel", "In Review")));

        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("Scheduled");
    }

    @Test
    void aDeletedMessagePostsAFreshOneCarryingEveryCurrentLine() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(DiscordProvider.EditOutcome.NOT_FOUND);
        when(discord.postReturningId(WEBHOOK, "ROLLUP")).thenReturn(posted("msg-new"));

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
    }

    @Test
    void aRateLimitedEditKeepsTheMessageSavesTheLinesAndSendsNothingElse() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(DiscordProvider.EditOutcome.RATE_LIMITED);

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        assertThat(r.getMessageId()).isEqualTo("msg-1");
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
        verify(discord, never()).postReturningId(anyString(), anyString());
    }

    @Test
    void aFailedEditPostsAFreshMessageWithAllLinesAndDoesNotBumpUpdatedAtOnTheOldOne() {
        OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);
        NotificationRollup r = row(before, line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(DiscordProvider.EditOutcome.FAILED);
        when(discord.postReturningId(WEBHOOK, "ROLLUP")).thenReturn(posted("msg-new"));

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        // the old id is abandoned; the fresh message carries every current line
        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
        verify(discord, times(1)).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void aFailedEditFollowedByADefiniteFailedPostAbandonsTheMessageAndSendsPlainly() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        OffsetDateTime updated = r.getUpdatedAt();
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(DiscordProvider.EditOutcome.FAILED);
        NotificationMessage e = status("2", "IN_REVIEW", "In Review");
        when(discord.postReturningId(WEBHOOK, "ROLLUP")).thenReturn(post(DiscordProvider.PostOutcome.DEFINITE_FAILURE));

        assertThat(rollup(e)).isFalse();

        assertThat(result.fallbackToPlain()).containsExactly(e);
        assertThat(r.getUpdatedAt()).isEqualTo(updated); // never bumped
        verify(repository).delete(r); // so later events do not repeat the same failing PATCH
    }

    @Test
    void aRateLimitedPostIsHandledSoNothingPilesOn() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(post(DiscordProvider.PostOutcome.RATE_LIMITED));

        assertThat(rollup(status("1", "IN_REVIEW", "In Review"))).isTrue();

        verify(repository, never()).save(any());
    }

    @Test
    void aRateLimitedPostForAnExpiredRowLeavesTheRowUntouched() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(30), line("1", 0, "IN_REVIEW", "In Review", null));
        OffsetDateTime updated = r.getUpdatedAt();
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(post(DiscordProvider.PostOutcome.RATE_LIMITED));

        // dropped with a warning, and NOT sent plainly to a webhook that is rate-limited
        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue();

        assertThat(r.getMessageId()).isEqualTo("msg-1");
        assertThat(r.getUpdatedAt()).isEqualTo(updated);
        assertThat(r.getLines()).containsOnlyKeys("1");
        verify(repository, never()).save(any());
    }

    @Test
    void anAmbiguousPostIsDroppedWithoutAFallbackAndLeavesTheRowUntouched() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(30), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(post(DiscordProvider.PostOutcome.AMBIGUOUS));

        assertThat(rollup(status("2", "IN_REVIEW", "In Review"))).isTrue(); // nothing to resend: it may exist

        assertThat(r.getMessageId()).isEqualTo("msg-1");
        verify(repository, never()).save(any());
    }

    @Test
    void aDefinitelyFailedPostFallsBackToAPlainSendOfThatEvent() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        NotificationMessage e = status("2", "IN_REVIEW", "In Review");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(post(DiscordProvider.PostOutcome.DEFINITE_FAILURE));

        assertThat(rollup(e)).isFalse();

        assertThat(result.fallbackToPlain()).containsExactly(e);
        verify(repository, never()).save(any());
    }

    @Test
    void aMessageOlderThanFourWindowsIsNotEditedEvenIfStillBeingTouched() {
        config.setGroupWindowMinutes(15);
        // touched a minute ago, but started 90 minutes ago: past 4 x 15 = 60 minutes
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        r.setStartedAt(OffsetDateTime.now().minusMinutes(90));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("msg-2"));

        rollup(status("2", "IN_REVIEW", "In Review"));

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-2");
        assertThat(r.getLines()).containsOnlyKeys("2");
    }

    @Test
    void theAgeCapNeverExceedsFourHoursWhateverTheWindow() {
        config.setGroupWindowMinutes(1440);
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        r.setStartedAt(OffsetDateTime.now().minusHours(5));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("msg-2"));

        rollup(status("2", "IN_REVIEW", "In Review"));

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void theConfigIsReadAfterTheLockSoADisabledOrEachChannelIsHonoured() {
        NotificationGroupConfig fresh = new NotificationGroupConfig();
        fresh.setId(CONFIG_ID);
        fresh.setProvider(ProviderType.DISCORD);
        fresh.setWebhookUrl(WEBHOOK);
        fresh.setEnabledEventTypes(new java.util.HashSet<>(config.getEnabledEventTypes()));

        // switched to EACH while the event waited: caller sends plainly
        fresh.setDeliveryMode(DeliveryMode.EACH);
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(fresh));
        assertThat(rollup(status("1", "X", "X"))).isFalse();

        // disabled meanwhile: dropped (handled, nothing sent)
        fresh.setDeliveryMode(DeliveryMode.GROUPED);
        fresh.setEnabled(false);
        assertThat(rollup(status("1", "X", "X"))).isTrue();

        // no longer opted into the event type: dropped
        fresh.setEnabled(true);
        fresh.setEnabledEventTypes(new java.util.HashSet<>());
        assertThat(rollup(status("1", "X", "X"))).isTrue();

        // deleted: dropped
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        assertThat(rollup(status("1", "X", "X"))).isTrue();

        verify(discord, never()).postReturningId(anyString(), anyString());
    }

    @Test
    void theWebhookIsTheOneReadAfterTheLock() {
        NotificationGroupConfig fresh = new NotificationGroupConfig();
        fresh.setId(CONFIG_ID);
        fresh.setProvider(ProviderType.DISCORD);
        fresh.setWebhookUrl("https://discord.com/api/webhooks/2/new");
        fresh.setEnabledEventTypes(new java.util.HashSet<>(config.getEnabledEventTypes()));
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.of(fresh));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId("https://discord.com/api/webhooks/2/new", "SINGLE")).thenReturn(posted("m"));

        assertThat(rollup(status("1", "X", "X"))).isTrue();
    }

    @Test
    void theLockWaitIsBoundedBeforeTheChannelIsLocked() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m"));

        rollup(status("1", "X", "X"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).limitLockWait();
        order.verify(repository).lockChannel(CONFIG_ID);
    }

    @Test
    void aLockFailureFallsBackToAPlainSend() {
        when(repository.lockChannel(CONFIG_ID)).thenThrow(new IllegalStateException("lock timeout"));

        assertThat(rollup(status("1", "X", "X"))).isFalse();
    }

    @Test
    void aSingleItemThatWasApprovedAndThenMovedKeepsBothInItsMessage() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.editMessage(WEBHOOK, "msg-1", "COMBINED")).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(List.of(review("1", "APPROVED"), status("1", "SCHEDULED", "Scheduled")));

        ArgumentCaptor<RollupLine> l = ArgumentCaptor.forClass(RollupLine.class);
        verify(discord).buildSinglePayload(l.capture());
        assertThat(l.getValue().moved()).isTrue();
        assertThat(l.getValue().statusLabel()).isEqualTo("Scheduled");
        assertThat(l.getValue().verdictNote()).isEqualTo("approved by Bryan");
    }

    @Test
    void anApprovalFollowedByTwoMovesIsOneCallShowingScheduledAndApprovedBy() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.editMessage(WEBHOOK, "msg-1", "COMBINED")).thenReturn(DiscordProvider.EditOutcome.OK);

        assertThat(rollup(List.of(review("1", "APPROVED"),
                status("1", "APPROVED", "Approved"), status("1", "SCHEDULED", "Scheduled")))).isTrue();

        verify(discord, times(1)).editMessage(anyString(), anyString(), anyString());
        verify(discord, never()).postReturningId(anyString(), anyString());
        verify(repository, times(1)).lockChannel(CONFIG_ID);
        verify(repository, times(1)).findById(CONFIG_ID);
        verify(configRepository, times(1)).findById(CONFIG_ID);
        ArgumentCaptor<RollupLine> l = ArgumentCaptor.forClass(RollupLine.class);
        verify(discord).buildSinglePayload(l.capture());
        assertThat(l.getValue().statusLabel()).isEqualTo("Scheduled");
        assertThat(l.getValue().verdictNote()).isEqualTo("approved by Bryan");
        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("Scheduled");
    }

    @Test
    void aBatchOnAFreshChannelPostsOnce() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.postReturningId(WEBHOOK, "COMBINED")).thenReturn(posted("m-1"));

        assertThat(rollup(List.of(review("1", "APPROVED"),
                status("1", "SCHEDULED", "Scheduled")))).isTrue();

        verify(discord, times(1)).postReturningId(anyString(), anyString());
        ArgumentCaptor<NotificationRollup> saved = ArgumentCaptor.forClass(NotificationRollup.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getLines().get("1").statusLabel()).isEqualTo("Scheduled");
    }

    @Test
    void aBatchSpanningACapacityRolloverFinishesTheFullMessageThenPostsTheRest() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1));
        for (int i = 0; i < NotificationRollupService.MAX_LINES; i++) {
            r.getLines().put("x" + i, line("x" + i, i, "IN_REVIEW", "In Review", null));
        }
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("FULL");
        when(discord.buildSinglePayload(any())).thenReturn("REST");
        when(discord.editMessage(WEBHOOK, "msg-1", "FULL")).thenReturn(DiscordProvider.EditOutcome.OK);
        when(discord.postReturningId(WEBHOOK, "REST")).thenReturn(posted("msg-new"));

        // an existing item changes (fits), then a new item and its approval overflow into a new message
        assertThat(rollup(List.of(status("x0", "APPROVED", "Approved"),
                review("new", "APPROVED"), status("new", "SCHEDULED", "Scheduled")))).isTrue();

        verify(discord, times(1)).editMessage(anyString(), anyString(), anyString());
        verify(discord, times(1)).postReturningId(anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("new");
        assertThat(r.getLines().get("new").statusLabel()).isEqualTo("Scheduled");
        assertThat(r.getLines().get("new").verdictNote()).isEqualTo("approved by Bryan");
    }

    @Test
    void aBatchForAnEachChannelFallsBackWithoutAnyDiscordCall() {
        config.setDeliveryMode(DeliveryMode.EACH);

        assertThat(rollup(List.of(review("1", "APPROVED"), status("1", "SCHEDULED", "Scheduled"))))
                .isFalse();

        verifyNoInteractions(discord);
    }

    @Test
    void aBatchThatCouldNotPostAnythingFallsBack() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.postReturningId(WEBHOOK, "COMBINED")).thenReturn(post(DiscordProvider.PostOutcome.DEFINITE_FAILURE));

        assertThat(rollup(List.of(review("1", "APPROVED"), status("1", "SCHEDULED", "Scheduled"))))
                .isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void aLoneReviewEventKeepsTodaysFormat() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m"));

        rollup(review("1", "APPROVED"));

        verify(discord, never()).buildSinglePayload(any());
    }

    @Test
    void aLongReviewerNameIsCappedInTheNote() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(DiscordProvider.EditOutcome.OK);

        rollup(NotificationMessage.of(EventType.REVIEW_SUBMITTED, PROJECT_ID, Map.of(
                "workItemId", "1", "workItemTitle", "T", "verdict", "APPROVED", "reviewerName", "B".repeat(300))));

        assertThat(r.getLines().get("1").verdictNote().length()).isLessThanOrEqualTo("approved by ".length() + 80);
    }

    @Test
    void aFailedPostLeavesTheRowUntouchedAndReportsNotHandled() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(post(DiscordProvider.PostOutcome.DEFINITE_FAILURE));

        assertThat(rollup(status("1", "IN_REVIEW", "In Review"))).isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void aFullMessageStartsAFreshOneForTheNextNewItem() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1));
        for (int i = 0; i < NotificationRollupService.MAX_LINES; i++) {
            r.getLines().put("x" + i, line("x" + i, i, "IN_REVIEW", "In Review", null));
        }
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("msg-new"));

        rollup(status("new", "IN_REVIEW", "In Review"));

        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("new");
        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void commentsAndBlockedOrManualEventsAreNeverRolledUp() {
        for (EventType type : new EventType[]{EventType.COMMENT_ADDED, EventType.COMMENT_REPLY,
                EventType.AUTO_TRANSITION_BLOCKED, EventType.POST_AWAITING_MANUAL, EventType.WORKFLOW_RUN_FAILED}) {
            assertThat(rollup(NotificationMessage.of(type, PROJECT_ID, Map.of("workItemId", "1"))))
                    .as(type.name()).isFalse();
        }
        verify(repository, never()).lockChannel(anyString());
    }

    @Test
    void nonDiscordChannelsAreNotRolledUp() {
        config.setProvider(ProviderType.SLACK);
        assertThat(rollup(status("1", "X", "X"))).isFalse();
    }

    @Test
    void aThrowingProviderNeverEscapes() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(rollup(status("1", "X", "X"))).isFalse();
    }

    private NotificationRollup fullRow() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1));
        for (int i = 0; i < NotificationRollupService.MAX_LINES; i++) {
            r.getLines().put("x" + i, line("x" + i, i, "IN_REVIEW", "In Review", null));
        }
        return r;
    }

    /** x0 changes (fits), then 26 new items: chunk 0 closes at 25 lines, chunk 1 fills to 25, chunk 2 has one. */
    private List<NotificationMessage> overflowingBatch() {
        List<NotificationMessage> batch = new java.util.ArrayList<>();
        batch.add(status("x0", "APPROVED", "Approved"));
        for (int i = 0; i < 26; i++) {
            batch.add(status("n" + i, "IN_REVIEW", "In Review"));
        }
        return batch;
    }

    @Test
    void aBatchCanRollOverMoreThanOnceKeepingEveryMessageUnderTheCaps() {
        NotificationRollup r = fullRow();
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("MULTI");
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(WEBHOOK, "msg-1", "MULTI")).thenReturn(DiscordProvider.EditOutcome.OK);
        when(discord.postReturningId(eq(WEBHOOK), anyString())).thenReturn(posted("m2"), posted("m3"));

        assertThat(rollup(overflowingBatch())).isTrue();

        // one edit closes the current message, then one post per new chunk
        verify(discord, times(1)).editMessage(anyString(), anyString(), anyString());
        verify(discord, times(2)).postReturningId(anyString(), anyString());
        ArgumentCaptor<java.util.Collection<RollupLine>> rendered = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(discord, times(2)).buildRollupPayload(rendered.capture());
        assertThat(rendered.getAllValues()).allSatisfy(c ->
                assertThat(c.size()).isLessThanOrEqualTo(NotificationRollupService.MAX_LINES));
        assertThat(r.getMessageId()).isEqualTo("m3");
        assertThat(r.getLines()).containsOnlyKeys("n25");
    }

    @Test
    void aFailedPostAfterARolloverReturnsOnlyTheEventsInTheUnsentChunks() {
        NotificationRollup r = fullRow();
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("MULTI");
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(WEBHOOK, "msg-1", "MULTI")).thenReturn(DiscordProvider.EditOutcome.OK);
        when(discord.postReturningId(eq(WEBHOOK), anyString()))
                .thenReturn(posted("m2"), post(DiscordProvider.PostOutcome.DEFINITE_FAILURE));
        List<NotificationMessage> batch = overflowingBatch();

        assertThat(rollup(batch)).isFalse();

        // chunk 0 (x0) was edited and chunk 1 (n0..n24) posted; only chunk 2 (n25) goes out plainly
        assertThat(result.fallbackToPlain()).containsExactly(batch.get(batch.size() - 1));
        assertThat(r.getMessageId()).isEqualTo("m2");
        verify(discord, times(2)).postReturningId(anyString(), anyString());
    }

    @Test
    void aRateLimitedPostAfterARolloverStopsAndDropsTheRest() {
        NotificationRollup r = fullRow();
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("MULTI");
        when(discord.editMessage(WEBHOOK, "msg-1", "MULTI")).thenReturn(DiscordProvider.EditOutcome.OK);
        when(discord.postReturningId(eq(WEBHOOK), anyString())).thenReturn(post(DiscordProvider.PostOutcome.RATE_LIMITED));

        assertThat(rollup(overflowingBatch())).isTrue();

        verify(discord, times(1)).postReturningId(anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void aRateLimitedClosingEditStopsTheBatchWithNoPostAndWritesNothing() {
        NotificationRollup r = fullRow();
        OffsetDateTime updated = r.getUpdatedAt();
        Map<String, RollupLine> oldLines = new HashMap<>(r.getLines());
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("MULTI");
        when(discord.editMessage(WEBHOOK, "msg-1", "MULTI")).thenReturn(DiscordProvider.EditOutcome.RATE_LIMITED);

        assertThat(rollup(overflowingBatch())).isTrue(); // dropped, not plain-sent

        verify(discord, never()).postReturningId(anyString(), anyString());
        verify(repository, never()).save(any());
        assertThat(r.getUpdatedAt()).isEqualTo(updated);
        assertThat(r.getLines()).isEqualTo(oldLines);
    }

    @Test
    void aClosingEditIsSkippedWhenTheBatchChangedNothingInTheFullMessage() {
        NotificationRollup r = fullRow();
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m2"));

        assertThat(rollup(status("new", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void theCapacityCheckMeasuresTheRenderedBody() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1));
        // a few lines, each with a long link: the character cap, not the line cap, is what trips
        for (int i = 0; i < 20; i++) {
            r.getLines().put("x" + i, new RollupLine("x" + i, "RE-" + i, "T".repeat(100), "Post", "S", "Label",
                    "in_progress", "https://example.com/" + "p".repeat(80), "approved by Bryan", i));
        }
        assertThat(DiscordProvider.rollupBody(r.getLines().values()).length())
                .isGreaterThan(NotificationRollupService.MAX_BODY_CHARS);
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m2"));

        // 20 lines (under MAX_LINES) already render over the character cap, so a new item rolls over
        rollup(status("new", "IN_REVIEW", "In Review"));

        assertThat(r.getMessageId()).isEqualTo("m2");
        assertThat(r.getLines()).containsOnlyKeys("new");
    }

    @Test
    void theLatestPrLinkAndAssigneeAreKeptAndABlankNeverErasesThem() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), any())).thenReturn(DiscordProvider.EditOutcome.OK);
        NotificationMessage first = status("1", "IN_PROGRESS", "In Progress",
                Map.of("prUrl", "https://github.com/o/r/pull/1", "assigneeName", "Bryan"));
        NotificationMessage second = status("1", "DONE", "Done", Map.of("assigneeName", " "));

        rollup(List.of(first, second));

        assertThat(r.getLines().get("1").prUrl()).isEqualTo("https://github.com/o/r/pull/1");
        assertThat(r.getLines().get("1").assigneeName()).isEqualTo("Bryan");
    }
}
