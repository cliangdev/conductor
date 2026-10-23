package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.NotificationRollup;
import com.conductor.repository.NotificationRollupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationRollupServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONFIG_ID = "cfg-1";
    private static final String WEBHOOK = "https://discord.com/api/webhooks/1/t";

    @Mock private NotificationRollupRepository repository;
    @Mock private DiscordProvider discord;

    private NotificationRollupService service;
    private NotificationGroupConfig config;

    @BeforeEach
    void setUp() {
        service = new NotificationRollupService(repository, discord, Duration.ofMinutes(15));
        config = new NotificationGroupConfig();
        config.setId(CONFIG_ID);
        config.setProjectId(PROJECT_ID);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl(WEBHOOK);
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
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn("m-9");

        assertThat(service.rollup(config, status("1", "IN_REVIEW", "In Review"))).isTrue();

        ArgumentCaptor<NotificationRollup> saved = ArgumentCaptor.forClass(NotificationRollup.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMessageId()).isEqualTo("m-9");
        assertThat(saved.getValue().getLines()).containsOnlyKeys("1");
    }

    @Test
    void eventInsideTheWindowEditsTheExistingMessage() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(2), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(true);

        assertThat(service.rollup(config, status("2", "IN_REVIEW", "In Review"))).isTrue();

        verify(discord, never()).postReturningId(anyString(), anyString());
        assertThat(r.getLines()).containsOnlyKeys("1", "2");
        assertThat(r.getMessageId()).isEqualTo("msg-1");
    }

    @Test
    void eventAfterTheWindowPostsANewMessageStartingFromThatEventAlone() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(30), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn("msg-2");

        assertThat(service.rollup(config, status("2", "SCHEDULED", "Scheduled"))).isTrue();

        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
        assertThat(r.getMessageId()).isEqualTo("msg-2");
        assertThat(r.getLines()).containsOnlyKeys("2");
    }

    @Test
    void sameItemsEventsCollapseToOneLineHoldingTheLatestState() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(true);

        service.rollup(config, status("1", "APPROVED", "Approved"));

        assertThat(r.getLines()).hasSize(1);
        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("Approved");
        // one item: rendered as today's single message
        verify(discord).editMessage(WEBHOOK, "msg-1", "SINGLE");
    }

    @Test
    void verdictNoteSurvivesTheNextStatusChange() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(true);

        service.rollup(config, review("1", "APPROVED"));
        assertThat(r.getLines().get("1").verdictNote()).isEqualTo("approved by Bryan");
        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("In Review");

        service.rollup(config, status("1", "SCHEDULED", "Scheduled"));
        RollupLine l = r.getLines().get("1");
        assertThat(l.statusLabel()).isEqualTo("Scheduled");
        assertThat(l.verdictNote()).isEqualTo("approved by Bryan");
    }

    @Test
    void reviewerAssignedOnlyAddsTheItemAndDoesNotTouchExistingState() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "SCHEDULED", "Scheduled", "approved by Bryan"));
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.editMessage(anyString(), anyString(), anyString())).thenReturn(true);

        service.rollup(config, NotificationMessage.of(EventType.REVIEWER_ASSIGNED, PROJECT_ID, Map.of(
                "workItemId", "1", "workItemTitle", "Title 1", "status", "IN_REVIEW", "statusLabel", "In Review")));

        assertThat(r.getLines().get("1").statusLabel()).isEqualTo("Scheduled");
    }

    @Test
    void aFailedEditPostsAFreshMessageAndResetsTheRow() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1), line("1", 0, "IN_REVIEW", "In Review", null));
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.buildRollupPayload(any())).thenReturn("ROLLUP");
        when(discord.editMessage(WEBHOOK, "msg-1", "ROLLUP")).thenReturn(false);
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn("msg-new");

        assertThat(service.rollup(config, status("2", "IN_REVIEW", "In Review"))).isTrue();

        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("2");
    }

    @Test
    void aFailedPostLeavesTheRowUntouchedAndReportsNotHandled() {
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn(null);

        assertThat(service.rollup(config, status("1", "IN_REVIEW", "In Review"))).isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void aFullMessageStartsAFreshOneForTheNextNewItem() {
        NotificationRollup r = row(OffsetDateTime.now().minusMinutes(1));
        for (int i = 0; i < NotificationRollupService.MAX_LINES; i++) {
            r.getLines().put("x" + i, line("x" + i, i, "IN_REVIEW", "In Review", null));
        }
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.of(r));
        when(discord.format(any())).thenReturn("SINGLE");
        when(discord.postReturningId(WEBHOOK, "SINGLE")).thenReturn("msg-new");

        service.rollup(config, status("new", "IN_REVIEW", "In Review"));

        assertThat(r.getMessageId()).isEqualTo("msg-new");
        assertThat(r.getLines()).containsOnlyKeys("new");
        verify(discord, never()).editMessage(anyString(), anyString(), anyString());
    }

    @Test
    void commentsAndBlockedOrManualEventsAreNeverRolledUp() {
        for (EventType type : new EventType[]{EventType.COMMENT_ADDED, EventType.COMMENT_REPLY,
                EventType.AUTO_TRANSITION_BLOCKED, EventType.POST_AWAITING_MANUAL, EventType.WORKFLOW_RUN_FAILED}) {
            assertThat(service.rollup(config, NotificationMessage.of(type, PROJECT_ID, Map.of("workItemId", "1"))))
                    .as(type.name()).isFalse();
        }
        verify(repository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void nonDiscordChannelsAreNotRolledUp() {
        config.setProvider(ProviderType.SLACK);
        assertThat(service.rollup(config, status("1", "X", "X"))).isFalse();
    }

    @Test
    void aThrowingProviderNeverEscapes() {
        when(repository.findByIdForUpdate(CONFIG_ID)).thenReturn(Optional.empty());
        when(discord.format(any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.rollup(config, status("1", "X", "X"))).isFalse();
    }
}
