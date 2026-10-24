package com.conductor.service;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.NotificationGroupRequest;
import com.conductor.generated.model.NotificationTestResponse;
import com.conductor.notification.ChannelGroup;
import com.conductor.notification.DeliveryMode;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationMessage;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.NotificationRollupRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class NotificationGroupService {

    private static final Logger log = LoggerFactory.getLogger(NotificationGroupService.class);

    static final int MIN_WINDOW_MINUTES = 1;
    static final int MAX_WINDOW_MINUTES = 1440;

    private final NotificationGroupConfigRepository groupConfigRepository;
    private final NotificationDeliveryService notificationDeliveryService;
    private final ProjectSecurityService projectSecurityService;
    private final NotificationRollupRepository rollupRepository;

    public NotificationGroupService(
            NotificationGroupConfigRepository groupConfigRepository,
            NotificationDeliveryService notificationDeliveryService,
            ProjectSecurityService projectSecurityService,
            NotificationRollupRepository rollupRepository) {
        this.groupConfigRepository = groupConfigRepository;
        this.notificationDeliveryService = notificationDeliveryService;
        this.projectSecurityService = projectSecurityService;
        this.rollupRepository = rollupRepository;
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

        Integer requestedWindow = request.getGroupWindowMinutes();
        if (requestedWindow != null && (requestedWindow < MIN_WINDOW_MINUTES || requestedWindow > MAX_WINDOW_MINUTES)) {
            throw new BusinessException("groupWindowMinutes must be between "
                    + MIN_WINDOW_MINUTES + " and " + MAX_WINDOW_MINUTES);
        }

        Optional<NotificationGroupConfig> existing =
                groupConfigRepository.findByProjectIdAndChannelGroup(projectId, channelGroup);

        boolean isNew = existing.isEmpty();
        NotificationGroupConfig config = existing.orElseGet(NotificationGroupConfig::new);

        if (isNew) {
            config.setProjectId(projectId);
            config.setChannelGroup(channelGroup);
        }

        String previousWebhook = config.getWebhookUrl();
        config.setProvider(ProviderType.valueOf(request.getProvider().getValue()));
        config.setWebhookUrl(request.getWebhookUrl());
        config.setEnabled(Boolean.TRUE.equals(request.getEnabled()));
        config.setEnabledEventTypes(new HashSet<>(enabledEventTypes));

        // Omitted keeps the stored value (a new config starts from the entity defaults), so clients that
        // predate the setting keep working.
        DeliveryMode previousMode = config.getDeliveryMode();
        int previousWindow = config.getGroupWindowMinutes();
        if (request.getDeliveryMode() != null) {
            config.setDeliveryMode(DeliveryMode.valueOf(request.getDeliveryMode().getValue()));
        }
        if (requestedWindow != null) {
            config.setGroupWindowMinutes(requestedWindow);
        }
        boolean deliveryChanged = !isNew
                && (previousMode != config.getDeliveryMode() || previousWindow != config.getGroupWindowMinutes()
                    || !Objects.equals(previousWebhook, config.getWebhookUrl()));

        NotificationGroupConfig saved = groupConfigRepository.save(config);
        if (deliveryChanged) {
            // Forget the current rolling message so the next grouped event starts a fresh one under the
            // new settings (mode, window or webhook), rather than editing a message the old ones opened.
            // The channel lock is taken first -- the same one a rollup holds while it works -- so an
            // in-flight rollup finishes before the row goes and cannot write back a row built from the
            // old settings; the rollup itself re-reads the config after taking that lock.
            rollupRepository.lockChannel(saved.getId());
            rollupRepository.deleteById(saved.getId());
        }
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
            NotificationMessage event = NotificationMessage.of(
                    testEventType,
                    projectId,
                    Map.of("test", "true", "description", "Test notification from Conductor")
            );
            // deliverTo, not deliver: a routed test would re-derive the destination from the event's
            // metadata, and a synthetic test event carries none — so testing the Publishing channel would
            // quietly send to the Issues one and report success about a channel it never touched.
            boolean sent = notificationDeliveryService.deliverTo(channelGroup, event);

            NotificationTestResponse response = new NotificationTestResponse();
            response.setSuccess(sent);
            response.setMessage(sent
                    ? "Test notification sent"
                    : "Nothing was sent — check the channel is enabled and its webhook URL is reachable.");
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
        if (!projectSecurityService.isProjectAdmin(projectId, userId)) {
            throw new ForbiddenException("Only ADMIN can manage notification channels");
        }
    }

    public record UpsertResult(NotificationGroupConfig config, boolean isNew) {}
}
