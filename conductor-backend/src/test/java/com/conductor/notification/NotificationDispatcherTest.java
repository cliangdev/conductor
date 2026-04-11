package com.conductor.notification;

import com.conductor.entity.NotificationChannelConfig;
import com.conductor.repository.NotificationChannelConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {

    @Mock
    private NotificationChannelConfigRepository channelConfigRepository;

    @Mock
    private DiscordProvider discordProvider;

    @InjectMocks
    private NotificationDispatcher dispatcher;

    private static final String PROJECT_ID = "proj-1";
    private static final String ISSUE_ID = "issue-1";
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/123/token";

    @Test
    void dispatchDoesNothingWhenNoConfigFound() {
        when(channelConfigRepository.findByProjectIdAndEventType(PROJECT_ID, EventType.ISSUE_SUBMITTED))
                .thenReturn(Optional.empty());

        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", "Test Issue"));

        dispatcher.dispatch(event);

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void dispatchDoesNothingWhenConfigDisabled() {
        NotificationChannelConfig config = configWith(ProviderType.DISCORD, WEBHOOK_URL, false);
        when(channelConfigRepository.findByProjectIdAndEventType(PROJECT_ID, EventType.ISSUE_SUBMITTED))
                .thenReturn(Optional.of(config));

        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", "Test Issue"));

        dispatcher.dispatch(event);

        verify(discordProvider, never()).format(any());
        verify(discordProvider, never()).send(anyString(), anyString());
    }

    @Test
    void dispatchCallsFormatThenSendForDiscordProvider() {
        NotificationChannelConfig config = configWith(ProviderType.DISCORD, WEBHOOK_URL, true);
        when(channelConfigRepository.findByProjectIdAndEventType(PROJECT_ID, EventType.ISSUE_SUBMITTED))
                .thenReturn(Optional.of(config));

        String formattedPayload = "{\"embeds\":[{\"title\":\"Test\"}]}";
        NotificationEvent event = NotificationEvent.of(
                EventType.ISSUE_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", "Test Issue"));

        when(discordProvider.format(event)).thenReturn(formattedPayload);

        dispatcher.dispatch(event);

        verify(discordProvider).format(event);
        verify(discordProvider).send(WEBHOOK_URL, formattedPayload);
    }

    @Test
    void dispatchUsesCorrectWebhookUrlFromConfig() {
        String customWebhookUrl = "https://discord.com/api/webhooks/999/custom";
        NotificationChannelConfig config = configWith(ProviderType.DISCORD, customWebhookUrl, true);
        when(channelConfigRepository.findByProjectIdAndEventType(PROJECT_ID, EventType.REVIEW_SUBMITTED))
                .thenReturn(Optional.of(config));

        String formattedPayload = "{\"embeds\":[]}";
        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEW_SUBMITTED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", "Test Issue", "verdict", "APPROVED"));

        when(discordProvider.format(event)).thenReturn(formattedPayload);

        dispatcher.dispatch(event);

        verify(discordProvider).send(customWebhookUrl, formattedPayload);
    }

    @Test
    void dispatchHandlesExceptionFromProviderGracefully() {
        NotificationChannelConfig config = configWith(ProviderType.DISCORD, WEBHOOK_URL, true);
        when(channelConfigRepository.findByProjectIdAndEventType(PROJECT_ID, EventType.REVIEWER_ASSIGNED))
                .thenReturn(Optional.of(config));

        NotificationEvent event = NotificationEvent.of(
                EventType.REVIEWER_ASSIGNED, PROJECT_ID,
                Map.of("issueId", ISSUE_ID, "issueTitle", "Test Issue", "reviewerName", "Alice"));

        when(discordProvider.format(event)).thenThrow(new RuntimeException("format failed"));

        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() -> dispatcher.dispatch(event));
    }

    private NotificationChannelConfig configWith(ProviderType providerType, String webhookUrl, boolean enabled) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setProjectId(PROJECT_ID);
        config.setProvider(providerType);
        config.setWebhookUrl(webhookUrl);
        config.setEnabled(enabled);
        return config;
    }
}
