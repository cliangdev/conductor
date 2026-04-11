package com.conductor.controller;

import com.conductor.entity.NotificationChannelConfig;
import com.conductor.entity.User;
import com.conductor.generated.api.NotificationsApi;
import com.conductor.generated.model.NotificationChannelRequest;
import com.conductor.generated.model.NotificationChannelResponse;
import com.conductor.generated.model.NotificationTestResponse;
import com.conductor.service.NotificationChannelService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class NotificationChannelController implements NotificationsApi {

    private final NotificationChannelService notificationChannelService;

    public NotificationChannelController(NotificationChannelService notificationChannelService) {
        this.notificationChannelService = notificationChannelService;
    }

    @Override
    public ResponseEntity<List<NotificationChannelResponse>> getNotificationChannels(String projectId) {
        User caller = currentUser();
        List<NotificationChannelConfig> configs = notificationChannelService.getChannels(projectId, caller);
        List<NotificationChannelResponse> responses = configs.stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    public ResponseEntity<NotificationChannelResponse> upsertNotificationChannel(
            String projectId, String eventType, NotificationChannelRequest request) {
        User caller = currentUser();
        NotificationChannelService.UpsertResult result =
                notificationChannelService.upsertChannel(projectId, eventType, request, caller);
        NotificationChannelResponse response = toResponse(result.config());
        if (result.isNew()) {
            return ResponseEntity.status(201).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteNotificationChannel(String projectId, String eventType) {
        User caller = currentUser();
        notificationChannelService.deleteChannel(projectId, eventType, caller);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<NotificationTestResponse> testNotificationChannel(String projectId, String eventType) {
        User caller = currentUser();
        NotificationTestResponse response = notificationChannelService.testChannel(projectId, eventType, caller);
        return ResponseEntity.ok(response);
    }

    private NotificationChannelResponse toResponse(NotificationChannelConfig config) {
        NotificationChannelResponse response = new NotificationChannelResponse();
        response.setEventType(config.getEventType() != null ? config.getEventType().name() : null);
        response.setProvider(config.getProvider() != null ? config.getProvider().name() : null);
        response.setWebhookUrl(config.getWebhookUrl());
        response.setEnabled(config.isEnabled());
        response.setCreatedAt(config.getCreatedAt());
        response.setUpdatedAt(config.getUpdatedAt());
        return response;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
