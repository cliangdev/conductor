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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
        return new DiscordProvider.PostResult(id, false);
    }

    private NotificationMessage status(String itemId, String toStatus, String label) {
        Map<String, String> m = new HashMap<>();
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

        assertThat(service.rollup(CONFIG_ID, status("1", "IN_REVIEW", "In Review"))).isTrue();

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

        assertThat(service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).postReturningId(anyString(), anyString());
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
        assertThat(r.getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void eachModeBypassesTheRollup() {
        config.setDeliveryMode(DeliveryMode.EACH);

        assertThat(service.rollup(CONFIG_ID, status("1", "IN_REVIEW", "In Review"))).isFalse();
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

        assertThat(service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"))).isTrue();

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

        assertThat(service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-2");
    }

    @Test
    void eventAfterTheWindowPostsANewMessageStartingFromThatEventAlone() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(30), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("msg-2"));

        assertThat(service.rollup(CONFIG_ID, status("2", "SCHEDULED", "Scheduled"))).isTrue();

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

        service.rollup(CONFIG_ID, status("1", "APPROVED", "Approved"));

        assertThat(r.getLines()).hasSize(1);
        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("Approved");
        // one item: rendered as today's single message
        verify(discord).editMessage(WEBHOOK, "msg-1", "SINGLE");
    }

    @Test
    void verdictNoteSurvivesTheNextStatusChange() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.editMessage(anyString(), anyString(), any())).thenReturn(DiscordProvider.EditOutcome.OK);

        service.rollup(CONFIG_ID, review("1", "APPROVED"));
        assertThat(r.getLines().get("1").verdictNote()).isEqualTo("approved by Bryan");
        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("In Review");

        service.rollup(CONFIG_ID, status("1", "SCHEDULED", "Scheduled"));
        RollupLine l = r.getLines().get("1");
        assertThat(l.statusLabel()).isEqualTo("Scheduled");
        assertThat(l.verdictNote()).isEqualTo("approved by Bryan");
    }

    @Test
    void reviewerAssignedOnlyAddsTheItemAndDoesNotTouchExistingState() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "SCHEDULED", "Scheduled", "approved by Bryan"));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(DiscordProvider.EditOutcome.OK);

        service.rollup(CONFIG_ID, NotificationMessage.of(EventType.REVIEWER_ASSIGNED, PROJECT_ID, Map.of(
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

        assertThat(service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"))).isTrue();

        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
    }

    @Test
    void aRateLimitedOrFailedEditKeepsTheMessageSavesTheLinesAndSendsNothingElse() {
        for (DiscordProvider.EditOutcome outcome : new DiscordProvider.EditOutcome[]{
                DiscordProvider.EditOutcome.RATE_LIMITED, DiscordProvider.EditOutcome.FAILED}) {
            NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
            when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
            when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
            when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(outcome);

            assertThat(service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"))).as(outcome.name()).isTrue();

            assertThat(r.getMessageId()).isEqualTo("msg-1");
            assertThat(r.getLines()).containsOnlyKeys("1", "2");
        }
        verify(discord, never()).postReturningId(anyString(), anyString());
    }

    @Test
    void aRateLimitedPostIsHandledSoNothingPilesOn() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(new DiscordProvider.PostResult(null, true));

        assertThat(service.rollup(CONFIG_ID, status("1", "IN_REVIEW", "In Review"))).isTrue();

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

        service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"));

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

        service.rollup(CONFIG_ID, status("2", "IN_REVIEW", "In Review"));

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
        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isFalse();

        // disabled meanwhile: dropped (handled, nothing sent)
        fresh.setDeliveryMode(DeliveryMode.GROUPED);
        fresh.setEnabled(false);
        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isTrue();

        // no longer opted into the event type: dropped
        fresh.setEnabled(true);
        fresh.setEnabledEventTypes(new java.util.HashSet<>());
        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isTrue();

        // deleted: dropped
        when(configRepository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isTrue();

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

        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isTrue();
    }

    @Test
    void theLockWaitIsBoundedBeforeTheChannelIsLocked() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m"));

        service.rollup(CONFIG_ID, status("1", "X", "X"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository);
        order.verify(repository).limitLockWait();
        order.verify(repository).lockChannel(CONFIG_ID);
    }

    @Test
    void aLockFailureFallsBackToAPlainSend() {
        when(repository.lockChannel(CONFIG_ID)).thenThrow(new IllegalStateException("lock timeout"));

        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isFalse();
    }

    @Test
    void aSingleItemThatWasApprovedAndThenMovedKeepsBothInItsMessage() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1),
                line("1", 0, "IN_REVIEW", "In Review", "approved by Bryan"));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildSinglePayload(any())).thenReturn("COMBINED");
        when(discord.editMessage(WEBHOOK, "msg-1", "COMBINED")).thenReturn(DiscordProvider.EditOutcome.OK);

        service.rollup(CONFIG_ID, status("1", "SCHEDULED", "Scheduled"));

        ArgumentCaptor<RollupLine> l = ArgumentCaptor.forClass(RollupLine.class);
        verify(discord).buildSinglePayload(l.capture());
        assertThat(l.getValue().moved()).isTrue();
        assertThat(l.getValue().statusLabel()).isEqualTo("Scheduled");
        assertThat(l.getValue().verdictNote()).isEqualTo("approved by Bryan");
    }

    @Test
    void aLoneReviewEventKeepsTodaysFormat() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(posted("m"));

        service.rollup(CONFIG_ID, review("1", "APPROVED"));

        verify(discord, never()).buildSinglePayload(any());
    }

    @Test
    void aLongReviewerNameIsCappedInTheNote() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(DiscordProvider.EditOutcome.OK);

        service.rollup(CONFIG_ID, NotificationMessage.of(EventType.REVIEW_SUBMITTED, PROJECT_ID, Map.of(
                "workItemId", "1", "workItemTitle", "T", "verdict", "APPROVED", "reviewerName", "B".repeat(300))));

        assertThat(r.getLines().get("1").verdictNote().length()).isLessThanOrEqualTo("approved by ".length() + 80);
    }

    @Test
    void aFailedPostLeavesTheRowUntouchedAndReportsNotHandled() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(new DiscordProvider.PostResult(null, false));

        assertThat(service.rollup(CONFIG_ID, status("1", "IN_REVIEW", "In Review"))).isFalse();

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

        service.rollup(CONFIG_ID, status("new", "IN_REVIEW", "In Review"));

        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("new");
        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void commentsAndBlockedOrManualEventsAreNeverRolledUp() {
        for (EventType type : new EventType[]{EventType.COMMENT_ADDED, EventType.COMMENT_REPLY,
                EventType.AUTO_TRANSITION_BLOCKED, EventType.POST_AWAITING_MANUAL, EventType.WORKFLOW_RUN_FAILED}) {
            assertThat(service.rollup(CONFIG_ID, NotificationMessage.of(type, PROJECT_ID, Map.of("workItemId", "1"))))
                    .as(type.name()).isFalse();
        }
        verify(repository, never()).lockChannel(anyString());
    }

    @Test
    void nonDiscordChannelsAreNotRolledUp() {
        config.setProvider(ProviderType.SLACK);
        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isFalse();
    }

    @Test
    void aThrowingProviderNeverEscapes() {
        when(repository.findById(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.rollup(CONFIG_ID, status("1", "X", "X"))).isFalse();
    }
}
