package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.NotificationGroupRequest;
import com.conductor.generated.model.NotificationTestResponse;
import com.conductor.notification.ChannelGroup;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.ProjectMemberRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class NotificationGroupService {

    private static final Logger log = LoggerFactory.getLogger(NotificationGroupService.class);

    private final NotificationGroupConfigRepository groupConfigRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final ProjectMemberRepository projectMemberRepository;

    public NotificationGroupService(
            NotificationGroupConfigRepository groupConfigRepository,
            NotificationDispatcher notificationDispatcher,
            ProjectMemberRepository projectMemberRepository) {
        this.groupConfigRepository = groupConfigRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationGroupConfig> getGroups(String projectId, User caller) {
        verifyAdmin(projectId, caller.getId());
        return groupConfigRepository.findByProjectId(projectId);
    }

    @Transactional
    public UpsertResult upsertGroup(String projectId, String channelGroupName,
                                    NotificationGroupRequest request, User caller) {
        verifyAdmin(projectId, caller.getId());

        ChannelGroup channelGroup = parseChannelGroup(channelGroupName);

        if (request.getWebhookUrl() == null || request.getWebhookUrl().isBlank()) {
            throw new BusinessException("webhookUrl must not be blank");
        }

        // Validate that all requested event types belong to this group
        List<String> groupEventNames = channelGroup.getEventTypes().stream()
                .map(EventType::name)
                .toList();
        List<String> enabledEventTypes = request.getEnabledEventTypes() != null
                ? request.getEnabledEventTypes()
                : List.of();
        for (String et : enabledEventTypes) {
            if (!groupEventNames.contains(et)) {
                throw new BusinessException("Event type " + et + " does not belong to group " + channelGroupName);
            }
        }

        Optional<NotificationGroupConfig> existing =
                groupConfigRepository.findByProjectIdAndChannelGroup(projectId, channelGroup);

        boolean isNew = existing.isEmpty();
        NotificationGroupConfig config = existing.orElseGet(NotificationGroupConfig::new);

        if (isNew) {
            config.setProjectId(projectId);
            config.setChannelGroup(channelGroup);
        }

        config.setProvider(ProviderType.valueOf(request.getProvider().getValue()));
        config.setWebhookUrl(request.getWebhookUrl());
        config.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        config.setEnabledEventTypes(new HashSet<>(enabledEventTypes));

        NotificationGroupConfig saved = groupConfigRepository.save(config);
        return new UpsertResult(saved, isNew);
    }

    @Transactional
    public void deleteGroup(String projectId, String channelGroupName, User caller) {
        verifyAdmin(projectId, caller.getId());

        ChannelGroup channelGroup = parseChannelGroup(channelGroupName);

        Optional<NotificationGroupConfig> existing =
                groupConfigRepository.findByProjectIdAndChannelGroup(projectId, channelGroup);
        if (existing.isEmpty()) {
            throw new EntityNotFoundException("No group config found for " + channelGroupName);
        }

        groupConfigRepository.deleteByProjectIdAndChannelGroup(projectId, channelGroup);
    }

    public NotificationTestResponse testGroup(String projectId, String channelGroupName, User caller) {
        verifyAdmin(projectId, caller.getId());

        ChannelGroup channelGroup = parseChannelGroup(channelGroupName);

        Optional<NotificationGroupConfig> configOpt =
                groupConfigRepository.findByProjectIdAndChannelGroup(projectId, channelGroup);

        if (configOpt.isEmpty()) {
            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(false);
            response.setMessage("No group config found for " + channelGroupName);
            return response;
        }

        // Use the first enabled event type in the group for the test, or the first group event
        NotificationGroupConfig config = configOpt.get();
        Set<String> enabled = config.getEnabledEventTypes();
        EventType testEventType = channelGroup.getEventTypes().stream()
                .filter(et -> enabled.contains(et.name()))
                .findFirst()
                .orElse(channelGroup.getEventTypes().get(0));

        try {
            NotificationEvent event = NotificationEvent.of(
                    testEventType,
                    projectId,
                    Map.of("test", "true", "description", "Test notification from Conductor")
            );
            notificationDispatcher.dispatch(event);

            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(true);
            response.setMessage("Test notification sent");
            return response;
        } catch (Exception e) {
            log.warn("Test notification failed for project {} group {}: {}", projectId, channelGroupName, e.getMessage());
            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(false);
            response.setMessage("Test notification failed: " + e.getMessage());
            return response;
        }
    }

    private ChannelGroup parseChannelGroup(String name) {
        try {
            return ChannelGroup.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid channel group: " + name);
        }
    }

    private void verifyAdmin(String projectId, String userId) {
        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        if (member.getRole() != MemberRole.ADMIN) {
            throw new ForbiddenException("Only ADMIN can manage notification channels");
        }
    }

    public record UpsertResult(NotificationGroupConfig config, boolean isNew) {}
}
