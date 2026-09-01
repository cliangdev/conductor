package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.repository.NotificationGroupConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private NotificationDeliveryService deliveryService;

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
