package com.conductor.service;

import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.NotificationGroupRequest;
import com.conductor.notification.ChannelGroup;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationMessage;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationGroupConfigRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationGroupServiceTest {

    @Mock
    private NotificationGroupConfigRepository groupConfigRepository;

    @Mock
    private NotificationDeliveryService notificationDeliveryService;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @InjectMocks
    private NotificationGroupService service;

    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("user-1");

        lenient().when(projectSecurityService.isProjectAdmin("proj-1", "user-1")).thenReturn(true);
    }

    @Test
    void getGroupsReturnsAllConfigsForProject() {
        NotificationGroupConfig config = buildConfig(ChannelGroup.ISSUES);
        when(groupConfigRepository.findByProjectId("proj-1")).thenReturn(List.of(config));

        List<NotificationGroupConfig> result = service.getGroups("proj-1", adminUser);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getChannelGroup()).isEqualTo(ChannelGroup.ISSUES);
    }

    @Test
    void getGroupsThrowsForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "user-1")).thenReturn(false);

        assertThatThrownBy(() -> service.getGroups("proj-1", adminUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void upsertGroupCreatesNewConfig() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.empty());
        when(groupConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationGroupRequest req = buildRequest("https://discord.com/test", List.of("WORK_ITEM_STATUS_CHANGED"));
        NotificationGroupService.UpsertResult result = service.upsertGroup("proj-1", "ISSUES", req, adminUser);

        assertThat(result.isNew()).isTrue();
        ArgumentCaptor<NotificationGroupConfig> captor = ArgumentCaptor.forClass(NotificationGroupConfig.class);
        verify(groupConfigRepository).save(captor.capture());
        NotificationGroupConfig saved = captor.getValue();
        assertThat(saved.getChannelGroup()).isEqualTo(ChannelGroup.ISSUES);
        assertThat(saved.getWebhookUrl()).isEqualTo("https://discord.com/test");
        assertThat(saved.getEnabledEventTypes()).containsExactly("WORK_ITEM_STATUS_CHANGED");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void upsertGroupUpdatesExistingConfig() {
        NotificationGroupConfig existing = buildConfig(ChannelGroup.ISSUES);
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.of(existing));
        when(groupConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationGroupRequest req = buildRequest("https://discord.com/updated",
                List.of("WORK_ITEM_STATUS_CHANGED", "REVIEW_SUBMITTED"));
        NotificationGroupService.UpsertResult result = service.upsertGroup("proj-1", "ISSUES", req, adminUser);

        assertThat(result.isNew()).isFalse();
        assertThat(result.config().getWebhookUrl()).isEqualTo("https://discord.com/updated");
        assertThat(result.config().getEnabledEventTypes()).containsExactlyInAnyOrder("WORK_ITEM_STATUS_CHANGED", "REVIEW_SUBMITTED");
    }

    @Test
    void upsertGroupThrowsForBlankWebhookUrl() {
        NotificationGroupRequest req = buildRequest("  ", List.of("WORK_ITEM_STATUS_CHANGED"));

        assertThatThrownBy(() -> service.upsertGroup("proj-1", "ISSUES", req, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    void upsertGroupThrowsForEventTypeNotInGroup() {
        NotificationGroupRequest req = buildRequest("https://discord.com/test",
                List.of("WORK_ITEM_STATUS_CHANGED"));

        assertThatThrownBy(() -> service.upsertGroup("proj-1", "MEMBERS", req, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("WORK_ITEM_STATUS_CHANGED");
    }

    @Test
    void upsertGroupThrowsForInvalidGroupName() {
        assertThatThrownBy(() -> service.upsertGroup("proj-1", "INVALID_GROUP",
                buildRequest("https://discord.com/test", List.of()), adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid channel group");
    }

    @Test
    void deleteGroupRemovesConfig() {
        NotificationGroupConfig config = buildConfig(ChannelGroup.ISSUES);
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        service.deleteGroup("proj-1", "ISSUES", adminUser);

        verify(groupConfigRepository).deleteByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES);
    }

    @Test
    void deleteGroupThrowsWhenNotFound() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGroup("proj-1", "ISSUES", adminUser))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void testGroupReturnsSuccessWhenConfigExists() {
        NotificationGroupConfig config = buildConfig(ChannelGroup.ISSUES);
        config.setEnabledEventTypes(Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        var response = service.testGroup("proj-1", "ISSUES", adminUser);

        assertThat(response.getSuccess()).isTrue();
        verify(notificationDeliveryService).deliver(any());
    }

    /**
     * Inverts the old (buggy) characterization pinned by {@code
     * NotificationDispatcherFanOutCharacterizationTest} and {@code
     * KnowledgeEventTapTest#testShapedEventWithNoWorkItemId_currentlyAlsoSubmitsWithUnknownRef}: before
     * the A5 refactor, {@code testGroup} published onto the {@code SignalBus}, so this synthetic {@code
     * {test: true}} event -- which carries no {@code workItemId} -- also fanned out to workflow
     * automation (a spurious {@code WorkflowRun}) and knowledge ingestion (a spurious submission with ref
     * {@code conductor:unknown}). {@link NotificationGroupService} now depends on {@link
     * NotificationDeliveryService} only -- it has no {@code SignalBus}, {@code WorkflowTriggerService}, or
     * knowledge-ingestion collaborator to call even by mistake, so "the only thing that happens is a chat
     * delivery attempt" is a structural guarantee, not just an unasserted one.
     */
    @Test
    void testGroupDeliversDirectlyWithoutFanningOutToWorkflowsOrKnowledge() {
        NotificationGroupConfig config = buildConfig(ChannelGroup.ISSUES);
        config.setEnabledEventTypes(Set.of("WORK_ITEM_STATUS_CHANGED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        service.testGroup("proj-1", "ISSUES", adminUser);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationDeliveryService).deliver(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsEntry("test", "true").doesNotContainKey("workItemId");
        verifyNoMoreInteractions(notificationDeliveryService);
    }

    @Test
    void testGroupReturnsFailureWhenNoConfig() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.empty());

        var response = service.testGroup("proj-1", "ISSUES", adminUser);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("ISSUES");
    }

    private NotificationGroupConfig buildConfig(ChannelGroup group) {
        NotificationGroupConfig config = new NotificationGroupConfig();
        config.setProjectId("proj-1");
        config.setChannelGroup(group);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl("https://discord.com/api/webhooks/123/abc");
        config.setEnabled(true);
        config.setEnabledEventTypes(Set.of());
        return config;
    }

    private NotificationGroupRequest buildRequest(String webhookUrl, List<String> eventTypes) {
        NotificationGroupRequest req = new NotificationGroupRequest();
        req.setProvider(NotificationGroupRequest.ProviderEnum.DISCORD);
        req.setWebhookUrl(webhookUrl);
        req.setEnabled(true);
        req.setEnabledEventTypes(eventTypes);
        return req;
    }
}
