package com.conductor.controller;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.User;
import com.conductor.generated.api.NotificationsApi;
import com.conductor.generated.model.NotificationChannelRequest;
import com.conductor.generated.model.NotificationChannelResponse;
import com.conductor.generated.model.NotificationEventConfig;
import com.conductor.generated.model.NotificationGroupRequest;
import com.conductor.generated.model.NotificationGroupResponse;
import com.conductor.generated.model.NotificationTestResponse;
import com.conductor.notification.ChannelGroup;
import com.conductor.notification.EventType;
import com.conductor.service.NotificationChannelService;
import com.conductor.service.NotificationGroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
public class NotificationGroupController implements NotificationsApi {

    private final NotificationChannelService notificationChannelService;
    private final NotificationGroupService notificationGroupService;

    public NotificationGroupController(
            NotificationChannelService notificationChannelService,
            NotificationGroupService notificationGroupService) {
        this.notificationChannelService = notificationChannelService;
        this.notificationGroupService = notificationGroupService;
    }

    @Override
    public ResponseEntity<List<NotificationChannelResponse>> getNotificationChannels(String projectId) {
        User caller = currentUser();
        return ResponseEntity.ok(
                notificationChannelService.getChannels(projectId, caller).stream()
                        .map(this::toChannelResponse)
                        .toList()
        );
    }

    @Override
    public ResponseEntity<NotificationChannelResponse> upsertNotificationChannel(
            String projectId, String eventType, NotificationChannelRequest request) {
        User caller = currentUser();
        NotificationChannelService.UpsertResult result =
                notificationChannelService.upsertChannel(projectId, eventType, request, caller);
        NotificationChannelResponse response = toChannelResponse(result.config());
        return result.isNew()
                ? ResponseEntity.status(201).body(response)
                : ResponseEntity.ok(response);
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
        return ResponseEntity.ok(notificationChannelService.testChannel(projectId, eventType, caller));
    }

    @Override
    public ResponseEntity<List<NotificationGroupResponse>> getNotificationGroups(String projectId) {
        User caller = currentUser();
        List<NotificationGroupConfig> configs = notificationGroupService.getGroups(projectId, caller);
        return ResponseEntity.ok(configs.stream().map(this::toGroupResponse).toList());
    }

    @Override
    public ResponseEntity<NotificationGroupResponse> upsertNotificationGroup(
            String projectId, String channelGroup, NotificationGroupRequest notificationGroupRequest) {
        User caller = currentUser();
        NotificationGroupService.UpsertResult result =
                notificationGroupService.upsertGroup(projectId, channelGroup, notificationGroupRequest, caller);
        NotificationGroupResponse response = toGroupResponse(result.config());
        return result.isNew()
                ? ResponseEntity.status(201).body(response)
                : ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteNotificationGroup(String projectId, String channelGroup) {
        User caller = currentUser();
        notificationGroupService.deleteGroup(projectId, channelGroup, caller);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<NotificationTestResponse> testNotificationGroup(String projectId, String channelGroup) {
        User caller = currentUser();
        return ResponseEntity.ok(notificationGroupService.testGroup(projectId, channelGroup, caller));
    }

    private NotificationChannelResponse toChannelResponse(com.conductor.entity.NotificationChannelConfig config) {
        NotificationChannelResponse response = new NotificationChannelResponse();
        response.setEventType(config.getEventType() != null ? config.getEventType().name() : null);
        response.setProvider(config.getProvider() != null ? config.getProvider().name() : null);
        response.setWebhookUrl(config.getWebhookUrl());
        response.setEnabled(config.isEnabled());
        response.setCreatedAt(config.getCreatedAt());
        response.setUpdatedAt(config.getUpdatedAt());
        return response;
    }

    private NotificationGroupResponse toGroupResponse(NotificationGroupConfig config) {
        NotificationGroupResponse response = new NotificationGroupResponse();
        ChannelGroup group = config.getChannelGroup();
        response.setChannelGroup(group != null ? group.name() : null);
        response.setLabel(group != null ? group.getLabel() : null);
        response.setProvider(config.getProvider() != null ? config.getProvider().name() : null);
        response.setWebhookUrl(config.getWebhookUrl());
        response.setEnabled(config.isEnabled());
        response.setCreatedAt(config.getCreatedAt());
        response.setUpdatedAt(config.getUpdatedAt());

        Set<String> enabledEventTypes = config.getEnabledEventTypes();
        if (group != null) {
            List<NotificationEventConfig> events = group.getEventTypes().stream()
                    .map(et -> {
                        NotificationEventConfig ec = new NotificationEventConfig();
                        ec.setEventType(et.name());
                        ec.setLabel(et.getDescription());
                        ec.setEnabled(enabledEventTypes.contains(et.name()));
                        return ec;
                    })
                    .toList();
            response.setEvents(events);
        }

        return response;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
