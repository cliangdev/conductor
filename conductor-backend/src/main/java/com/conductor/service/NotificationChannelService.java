package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.NotificationChannelConfig;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.NotificationChannelRequest;
import com.conductor.generated.model.NotificationTestResponse;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationChannelConfigRepository;
import com.conductor.repository.ProjectMemberRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class NotificationChannelService {

    private static final Logger log = LoggerFactory.getLogger(NotificationChannelService.class);

    private final NotificationChannelConfigRepository channelConfigRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final ProjectMemberRepository projectMemberRepository;

    public NotificationChannelService(
            NotificationChannelConfigRepository channelConfigRepository,
            NotificationDispatcher notificationDispatcher,
            ProjectMemberRepository projectMemberRepository) {
        this.channelConfigRepository = channelConfigRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationChannelConfig> getChannels(String projectId, User caller) {
        verifyAdmin(projectId, caller.getId());
        return channelConfigRepository.findByProjectId(projectId);
    }

    @Transactional
    public UpsertResult upsertChannel(String projectId, String eventTypeName,
                                      NotificationChannelRequest request, User caller) {
        verifyAdmin(projectId, caller.getId());

        EventType eventType = parseEventType(eventTypeName);

        if (request.getWebhookUrl() == null || request.getWebhookUrl().isBlank()) {
            throw new BusinessException("webhookUrl must not be blank");
        }

        Optional<NotificationChannelConfig> existing =
                channelConfigRepository.findByProjectIdAndEventType(projectId, eventType);

        boolean isNew = existing.isEmpty();
        NotificationChannelConfig config = existing.orElseGet(NotificationChannelConfig::new);

        if (isNew) {
            config.setProjectId(projectId);
            config.setEventType(eventType);
        }

        config.setProvider(ProviderType.valueOf(request.getProvider().getValue()));
        config.setWebhookUrl(request.getWebhookUrl());
        config.setEnabled(Boolean.TRUE.equals(request.getEnabled()));

        NotificationChannelConfig saved = channelConfigRepository.save(config);
        return new UpsertResult(saved, isNew);
    }

    @Transactional
    public void deleteChannel(String projectId, String eventTypeName, User caller) {
        verifyAdmin(projectId, caller.getId());

        EventType eventType = parseEventType(eventTypeName);

        Optional<NotificationChannelConfig> existing =
                channelConfigRepository.findByProjectIdAndEventType(projectId, eventType);
        if (existing.isEmpty()) {
            throw new EntityNotFoundException("No channel configured for event type " + eventTypeName);
        }

        channelConfigRepository.deleteByProjectIdAndEventType(projectId, eventType);
    }

    public NotificationTestResponse testChannel(String projectId, String eventTypeName, User caller) {
        verifyAdmin(projectId, caller.getId());

        EventType eventType = parseEventType(eventTypeName);

        Optional<NotificationChannelConfig> configOpt =
                channelConfigRepository.findByProjectIdAndEventType(projectId, eventType);

        if (configOpt.isEmpty()) {
            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(false);
            response.setMessage("No channel configured for event type " + eventTypeName);
            return response;
        }

        try {
            NotificationEvent event = NotificationEvent.of(
                    eventType,
                    projectId,
                    Map.of("test", "true", "description", "Test notification from Conductor")
            );
            notificationDispatcher.dispatch(event);

            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(true);
            response.setMessage("Test notification sent");
            return response;
        } catch (Exception e) {
            log.warn("Test notification failed for project {} eventType {}: {}", projectId, eventTypeName, e.getMessage());
            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(false);
            response.setMessage("Test notification failed: " + e.getMessage());
            return response;
        }
    }

    private EventType parseEventType(String eventTypeName) {
        try {
            return EventType.valueOf(eventTypeName);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid event type: " + eventTypeName);
        }
    }

    private void verifyAdmin(String projectId, String userId) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (member.getRole() != MemberRole.ADMIN) {
            throw new ForbiddenException("Only ADMIN can manage notification channels");
        }
    }

    public record UpsertResult(NotificationChannelConfig config, boolean isNew) {}
}
