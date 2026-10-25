package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.repository.NotificationGroupConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chat-delivery gating for {@link NotificationDeliveryService}. These six cases moved here verbatim
 * from the old {@code NotificationDispatcherTest} when delivery was extracted out of the dispatcher;
 * the sibling fan-out/ordering characterization (workflow triggers, lifecycle cascade, knowledge
 * ingestion all running off the same {@code SignalBus} publish) is covered per-subscriber by {@code
 * WorkflowAutomationSignalSubscriberTest}, {@code LifecycleSignalSubscriberTest}, {@code
 * KnowledgeSignalSinkTest}, and {@code NotificationSignalSinkTest}, plus the real end-to-end wiring in
 * {@code SignalBusWiringContextTest}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123/token";

    @Mock
    private NotificationGroupConfigRepository groupConfigRepository;

    @Mock
    private DiscordProvider discordProvider;

    @Mock
    private NotificationRollupService rollupService;

    @InjectMocks
    private NotificationDeliveryService deliveryService;

    /** Unless a test says otherwise the roll-up declines, so rolled-up events are sent plainly as before. */
    @org.junit.jupiter.api.BeforeEach
    void rollupDeclinesByDefault() {
        org.mockito.Mockito.lenient().when(rollupService.rollup(any(), anyList()))
                .thenAnswer(inv -> RollupResult.fallback(inv.getArgument(1)));
    }

    @Test
    void deliverDoesNothingWhenNoGroupConfigFound() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.empty());

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void deliverDoesNothingWhenGroupConfigDisabled() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, false,
                Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void deliverDoesNothingWhenEventTypeNotEnabledInGroup() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("REVIEW_SUBMITTED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void deliverSendsNotificationWhenGroupEnabledAndEventTypeEnabled() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        NotificationMessage event = eventOf(EventType.WORK_ITEM_STATUS_CHANGED);
        String formatted = "{\"embeds\":[{\"title\":\"Test\"}]}";
        when(discordProvider.format(event)).thenReturn(formatted);

        deliveryService.deliver(event);

        verify(discordProvider).format(event);
        verify(discordProvider).send(WEBHOOK_URL, formatted);
    }

    @Test
    void deliverUsesCorrectWebhookUrlFromGroupConfig() {
        String customUrl = "https://discord.com/api/webhooks/999/custom";
        NotificationGroupConfig config = groupConfig(ChannelGroup.MEMBERS, customUrl, true,
                Set.of("MEMBER_JOINED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.MEMBERS))
                .thenReturn(Optional.of(config));

        NotificationMessage event = NotificationMessage.of(EventType.MEMBER_JOINED, PROJECT_ID,
                Map.of("memberName", "Alice"));
        when(discordProvider.format(event)).thenReturn("{}");

        deliveryService.deliver(event);

        verify(discordProvider).send(customUrl, "{}");
    }

    @Test
    void deliverHandlesProviderExceptionGracefully() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("REVIEW_SUBMITTED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        NotificationMessage event = eventOf(EventType.REVIEW_SUBMITTED);
        when(discordProvider.format(event)).thenThrow(new RuntimeException("format failed"));

        assertThatNoException().isThrownBy(() -> deliveryService.deliver(event));
    }

    // ── Routing a publishing Workflow's events to their own channel ────────────────────────────

    private NotificationMessage publishingEventOf(EventType type) {
        return NotificationMessage.of(type, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID, ChannelGroup.META_PUBLISHES, "true"));
    }

    @Test
    void aPostsStatusChangeGoesToThePublishingChannelWhenOneIsConfigured() {
        NotificationGroupConfig publishing = groupConfig(ChannelGroup.PUBLISHING, "https://discord.test/pub",
                true, Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.PUBLISHING))
                .thenReturn(Optional.of(publishing));
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(publishingEventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider).send("https://discord.test/pub", "{}");
        // Never both: a Post's status change is one message, in one channel.
        verify(groupConfigRepository, never())
                .findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES);
    }

    @Test
    void aPostsStatusChangeFallsBackToTheIssuesChannelWhenPublishingIsNotConfigured() {
        // The property that makes this group additive: a project that had notifications before still has
        // them, in the channel it already chose, until it opts into a separate one.
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.PUBLISHING))
                .thenReturn(Optional.empty());
        NotificationGroupConfig issues = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(issues));
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(publishingEventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider).send(WEBHOOK_URL, "{}");
    }

    @Test
    void aPublishingChannelThatIsDisabledFallsThroughRatherThanSwallowingTheEvent() {
        NotificationGroupConfig publishing = groupConfig(ChannelGroup.PUBLISHING, "https://discord.test/pub",
                false, Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.PUBLISHING))
                .thenReturn(Optional.of(publishing));
        NotificationGroupConfig issues = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(issues));
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(publishingEventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider).send(WEBHOOK_URL, "{}");
    }

    @Test
    void anIssuesStatusChangeNeverReachesThePublishingChannel() {
        NotificationGroupConfig issues = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(issues));
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider).send(WEBHOOK_URL, "{}");
        verify(groupConfigRepository, never())
                .findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.PUBLISHING);
    }

    @Test
    void aManualPublishAlertIsSilentUntilAPublishingChannelExists() {
        // It belongs to no general group, so there is nothing to fall back to — and nothing sensible to
        // say in an Issues channel about a lane that project has not set up.
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.PUBLISHING))
                .thenReturn(Optional.empty());

        deliveryService.deliver(publishingEventOf(EventType.POST_AWAITING_MANUAL));

        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void deliverFallsBackToPlainSendWhenRollupDeclinesTheEvent() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of(EventType.WORK_ITEM_STATUS_CHANGED.name()));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));
        when(rollupService.rollup(any(), anyList()))
                .thenAnswer(inv -> RollupResult.fallback(inv.getArgument(1)));
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider).send(WEBHOOK_URL, "{}");
    }

    @Test
    void deliverDoesNotSendSeparatelyWhenRollupHandledTheEvent() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of(EventType.WORK_ITEM_STATUS_CHANGED.name()));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));
        when(rollupService.rollup(any(), anyList())).thenReturn(RollupResult.handled());

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void eventsThatNeverRollUpDoNotEvenAskTheRollupService() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of(EventType.COMMENT_ADDED.name()));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(eventOf(EventType.COMMENT_ADDED));

        verify(rollupService, never()).rollup(any(), anyList());
        verify(discordProvider).send(WEBHOOK_URL, "{}");
    }

    private NotificationGroupConfig groupedConfig() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of(EventType.WORK_ITEM_STATUS_CHANGED.name()));
        config.setId("cfg-1");
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));
        return config;
    }

    @Test
    void insideATransactionTheRollupWaitsForTheCommit() {
        groupedConfig();
        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));
            verify(rollupService, never()).rollup(any(), anyList());

            when(rollupService.rollup(any(), anyList())).thenReturn(RollupResult.handled());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

            verify(rollupService).rollup(eq("cfg-1"), anyList());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRolledBackCallerSendsNothing() {
        groupedConfig();
        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(rollupService, never()).rollup(any(), anyList());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void nothingThrownByTheRollupEscapesAfterCommit() {
        groupedConfig();
        when(rollupService.rollup(any(), anyList())).thenThrow(new IllegalStateException("boom"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));
            assertThatNoException().isThrownBy(() ->
                    TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private NotificationGroupConfig groupedConfigFor(ChannelGroup group, String id) {
        NotificationGroupConfig config = groupConfig(group, WEBHOOK_URL, true,
                Set.of(EventType.WORK_ITEM_STATUS_CHANGED.name(), EventType.REVIEW_SUBMITTED.name()));
        config.setId(id);
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, group))
                .thenReturn(Optional.of(config));
        return config;
    }

    private NotificationMessage statusEvent(String itemId) {
        return NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID, Map.of("workItemId", itemId));
    }

    @Test
    void eventsOfOneTransactionForOneChannelRollUpOnceInOrder() {
        groupedConfigFor(ChannelGroup.ISSUES, "cfg-1");
        NotificationMessage review = NotificationMessage.of(EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("workItemId", ISSUE_ID));
        NotificationMessage approved = statusEvent(ISSUE_ID);
        NotificationMessage scheduled = statusEvent(ISSUE_ID);
        when(rollupService.rollup(any(), anyList())).thenReturn(RollupResult.handled());

        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(review);
            deliveryService.deliver(approved);
            deliveryService.deliver(scheduled);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(rollupService, times(1)).rollup(any(), anyList());
        verify(rollupService).rollup("cfg-1", List.of(review, approved, scheduled));
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void eventsForTwoChannelsRollUpOncePerChannel() {
        groupedConfigFor(ChannelGroup.ISSUES, "cfg-issues");
        groupedConfigFor(ChannelGroup.PUBLISHING, "cfg-publishing");
        NotificationMessage a = statusEvent("a");
        NotificationMessage b = NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, PROJECT_ID,
                Map.of("workItemId", "b", ChannelGroup.META_PUBLISHES, "true"));
        when(rollupService.rollup(any(), anyList())).thenReturn(RollupResult.handled());

        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(a);
            deliveryService.deliver(b);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(rollupService, times(2)).rollup(anyString(), anyList());
        verify(rollupService, times(1)).rollup(eq("cfg-issues"), anyList());
        verify(rollupService, times(1)).rollup(eq("cfg-publishing"), anyList());
    }

    @Test
    void aDeclinedBatchSendsEachEventPlainlyInOrder() {
        groupedConfigFor(ChannelGroup.ISSUES, "cfg-1");
        NotificationMessage one = statusEvent("a");
        NotificationMessage two = statusEvent("b");
        when(rollupService.rollup(any(), anyList()))
                .thenAnswer(inv -> RollupResult.fallback(inv.getArgument(1)));
        when(discordProvider.format(any())).thenReturn("{}");

        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(one);
            deliveryService.deliver(two);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(discordProvider);
        order.verify(discordProvider).format(one);
        order.verify(discordProvider).format(two);
        verify(discordProvider, times(2)).send(WEBHOOK_URL, "{}");
    }

    @Test
    void aRolledBackTransactionMakesNoCallsAndTheNextOneStartsClean() {
        groupedConfigFor(ChannelGroup.ISSUES, "cfg-1");
        when(rollupService.rollup(any(), anyList())).thenReturn(RollupResult.handled());

        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(statusEvent("a"));
            deliveryService.deliver(statusEvent("b"));
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(rollupService, never()).rollup(any(), anyList());

        // a later transaction on the same thread sees none of the rolled-back events
        NotificationMessage later = statusEvent("c");
        TransactionSynchronizationManager.initSynchronization();
        try {
            deliveryService.deliver(later);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(rollupService).rollup("cfg-1", List.of(later));
    }

    @Test
    void anEachChannelSendsPlainlyWithoutAskingTheRollup() {
        NotificationGroupConfig config = groupedConfig();
        config.setDeliveryMode(DeliveryMode.EACH);
        when(discordProvider.format(any())).thenReturn("{}");

        deliveryService.deliver(eventOf(EventType.WORK_ITEM_STATUS_CHANGED));

        verify(rollupService, never()).rollup(any(), anyList());
        verify(discordProvider).send(WEBHOOK_URL, "{}");
    }

    private NotificationMessage eventOf(EventType type) {
        return NotificationMessage.of(type, PROJECT_ID, Map.of("workItemId", ISSUE_ID));
    }

    private NotificationGroupConfig groupConfig(ChannelGroup group, String webhookUrl,
                                                boolean enabled, Set<String> enabledEventTypes) {
        NotificationGroupConfig config = new NotificationGroupConfig();
        config.setProjectId(PROJECT_ID);
        config.setChannelGroup(group);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl(webhookUrl);
        config.setEnabled(enabled);
        config.setEnabledEventTypes(enabledEventTypes);
        return config;
    }
}
