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

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private NotificationGroupConfigRepository groupConfigRepository;

    @Mock
    private DiscordProvider discordProvider;

    @InjectMocks
    private NotificationDispatcher dispatcher;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123/token";

    @Test
    void dispatchDoesNothingWhenNoGroupConfigFound() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.empty());

        dispatcher.dispatch(eventOf(EventType.ISSUE_SUBMITTED));

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void dispatchDoesNothingWhenGroupConfigDisabled() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, false,
                Set.of("ISSUE_SUBMITTED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        dispatcher.dispatch(eventOf(EventType.ISSUE_SUBMITTED));

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void dispatchDoesNothingWhenEventTypeNotEnabledInGroup() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("ISSUE_APPROVED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        dispatcher.dispatch(eventOf(EventType.ISSUE_SUBMITTED));

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void dispatchSendsNotificationWhenGroupEnabledAndEventTypeEnabled() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("ISSUE_SUBMITTED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        NotificationEvent event = eventOf(EventType.ISSUE_SUBMITTED);
        String formatted = "{\"embeds\":[{\"title\":\"Test\"}]}";
        when(discordProvider.format(event)).thenReturn(formatted);

        dispatcher.dispatch(event);

        verify(discordProvider).format(event);
        verify(discordProvider).send(WEBHOOK_URL, formatted);
    }

    @Test
    void dispatchUsesCorrectWebhookUrlFromGroupConfig() {
        String customUrl = "https://discord.com/api/webhooks/999/custom";
        NotificationGroupConfig config = groupConfig(ChannelGroup.MEMBERS, customUrl, true,
                Set.of("MEMBER_JOINED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.MEMBERS))
                .thenReturn(Optional.of(config));

        NotificationEvent event = NotificationEvent.of(EventType.MEMBER_JOINED, PROJECT_ID,
                Map.of("memberName", "Alice"));
        when(discordProvider.format(event)).thenReturn("{}");

        dispatcher.dispatch(event);

        verify(discordProvider).send(customUrl, "{}");
    }

    @Test
    void dispatchHandlesProviderExceptionGracefully() {
        NotificationGroupConfig config = groupConfig(ChannelGroup.ISSUES, WEBHOOK_URL, true,
                Set.of("REVIEW_SUBMITTED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup(PROJECT_ID, ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        NotificationEvent event = eventOf(EventType.REVIEW_SUBMITTED);
        when(discordProvider.format(event)).thenThrow(new RuntimeException("format failed"));

        assertThatNoException().isThrownBy(() -> dispatcher.dispatch(event));
    }

    private NotificationEvent eventOf(EventType type) {
        return NotificationEvent.of(type, PROJECT_ID, Map.of("issueId", ISSUE_ID));
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
