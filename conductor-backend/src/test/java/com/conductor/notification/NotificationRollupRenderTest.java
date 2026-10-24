package com.conductor.notification;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.NotificationRollup;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.NotificationRollupRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The roll-up wired to a real {@link DiscordProvider}: asserts on the JSON that would actually be sent,
 * where {@link NotificationRollupServiceTest} (which mocks the provider) can only assert on what the
 * service hands it.
 */
class NotificationRollupRenderTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/1/t";

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anApprovalFollowedByAnAutoMoveStillShowsBothInTheEditedMessage() {
        RestTemplate rollupTemplate = mock(RestTemplate.class);
        DiscordProvider discord = new DiscordProvider(mock(RestTemplate.class), rollupTemplate, "http://f");
        NotificationRollupRepository rollups = mock(NotificationRollupRepository.class);
        NotificationGroupConfigRepository configs = mock(NotificationGroupConfigRepository.class);
        NotificationGroupConfig config = new NotificationGroupConfig();
        config.setId("cfg");
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl(WEBHOOK);
        config.setEnabledEventTypes(new HashSet<>(Set.of("REVIEW_SUBMITTED", "WORK_ITEM_STATUS_CHANGED")));
        when(configs.findById("cfg")).thenReturn(Optional.of(config));
        when(rollups.lockChannel("cfg")).thenReturn(Optional.of("cfg"));
        NotificationRollupService service = new NotificationRollupService(rollups, configs, discord);

        // 1. the approval opens the message
        when(rollupTemplate.postForEntity(any(java.net.URI.class), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("id", "m1")));
        when(rollups.findById("cfg")).thenReturn(Optional.empty());
        Map<String, String> review = new HashMap<>(Map.of(
                "workItemId", "w1", "workItemTitle", "Launch post", "noun", "Post", "displayId", "RE-1",
                "verdict", "APPROVED", "reviewerName", "Bryan", "status", "IN_REVIEW", "statusLabel", "In Review"));
        service.rollup("cfg", NotificationMessage.of(EventType.REVIEW_SUBMITTED, "p", review));
        ArgumentCaptor<NotificationRollup> saved = ArgumentCaptor.forClass(NotificationRollup.class);
        verify(rollups).save(saved.capture());
        NotificationRollup row = saved.getValue();

        // 2. the auto-move edits it
        when(rollups.findById("cfg")).thenReturn(Optional.of(row));
        when(rollupTemplate.exchange(any(java.net.URI.class), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        Map<String, String> moved = new HashMap<>(Map.of(
                "workItemId", "w1", "workItemTitle", "Launch post", "noun", "Post", "displayId", "RE-1",
                "toStatus", "SCHEDULED", "toStatusLabel", "Scheduled", "toCategory", "in_progress"));
        service.rollup("cfg", NotificationMessage.of(EventType.WORK_ITEM_STATUS_CHANGED, "p", moved));

        ArgumentCaptor<HttpEntity> patch = ArgumentCaptor.forClass(HttpEntity.class);
        verify(rollupTemplate).exchange(any(java.net.URI.class), eq(HttpMethod.PATCH), patch.capture(), eq(String.class));
        String body = (String) patch.getValue().getBody();
        assertThat(body).contains("Post moved to Scheduled").contains("approved by Bryan")
                .contains("\"allowed_mentions\":{\"parse\":[]}");
    }
}
