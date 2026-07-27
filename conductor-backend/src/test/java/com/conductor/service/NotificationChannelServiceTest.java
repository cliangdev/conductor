package com.conductor.service;

import com.conductor.entity.NotificationChannelConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.NotificationChannelRequest;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationMessage;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationChannelConfigRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationChannelServiceTest {

    @Mock
    private NotificationChannelConfigRepository channelConfigRepository;

    @Mock
    private NotificationDeliveryService notificationDeliveryService;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @InjectMocks
    private NotificationChannelService service;

    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("user-1");

        lenient().when(projectSecurityService.isProjectAdmin("proj-1", "user-1")).thenReturn(true);
    }

    @Test
    void getChannelsReturnsAllConfigsForProject() {
        NotificationChannelConfig config = buildConfig(EventType.WORK_ITEM_STATUS_CHANGED);
        when(channelConfigRepository.findByProjectId("proj-1")).thenReturn(List.of(config));

        List<NotificationChannelConfig> result = service.getChannels("proj-1", adminUser);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EventType.WORK_ITEM_STATUS_CHANGED);
    }

    @Test
    void getChannelsThrowsForNonAdmin() {
        when(projectSecurityService.isProjectAdmin("proj-1", "user-1")).thenReturn(false);

        assertThatThrownBy(() -> service.getChannels("proj-1", adminUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void upsertChannelCreatesNewConfig() {
        when(channelConfigRepository.findByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED))
                .thenReturn(Optional.empty());
        when(channelConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationChannelRequest req = buildRequest("https://discord.com/test");
        NotificationChannelService.UpsertResult result =
                service.upsertChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", req, adminUser);

        assertThat(result.isNew()).isTrue();
        ArgumentCaptor<NotificationChannelConfig> captor = ArgumentCaptor.forClass(NotificationChannelConfig.class);
        verify(channelConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(EventType.WORK_ITEM_STATUS_CHANGED);
        assertThat(captor.getValue().getWebhookUrl()).isEqualTo("https://discord.com/test");
    }

    @Test
    void upsertChannelThrowsForBlankWebhookUrl() {
        NotificationChannelRequest req = buildRequest("  ");

        assertThatThrownBy(() -> service.upsertChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", req, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    void upsertChannelThrowsForInvalidEventType() {
        assertThatThrownBy(() -> service.upsertChannel("proj-1", "NOT_A_REAL_TYPE",
                buildRequest("https://discord.com/test"), adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid event type");
    }

    @Test
    void deleteChannelRemovesConfig() {
        NotificationChannelConfig config = buildConfig(EventType.WORK_ITEM_STATUS_CHANGED);
        when(channelConfigRepository.findByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED))
                .thenReturn(Optional.of(config));

        service.deleteChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", adminUser);

        verify(channelConfigRepository).deleteByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED);
    }

    @Test
    void deleteChannelThrowsWhenNotFound() {
        when(channelConfigRepository.findByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", adminUser))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void testChannelReturnsSuccessWhenConfigExists() {
        NotificationChannelConfig config = buildConfig(EventType.WORK_ITEM_STATUS_CHANGED);
        when(channelConfigRepository.findByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED))
                .thenReturn(Optional.of(config));

        var response = service.testChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", adminUser);

        assertThat(response.getSuccess()).isTrue();
        verify(notificationDeliveryService).deliver(any());
    }

    @Test
    void testChannelReturnsFailureWhenNoConfig() {
        when(channelConfigRepository.findByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED))
                .thenReturn(Optional.empty());

        var response = service.testChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", adminUser);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getMessage()).contains("WORK_ITEM_STATUS_CHANGED");
    }

    /**
     * Mirrors {@code NotificationGroupServiceTest#testGroupDeliversDirectlyWithoutFanningOutToWorkflowsOrKnowledge}:
     * before the A5 refactor this test-notification path published onto the {@code SignalBus} (via {@code
     * NotificationDispatcher}), so a synthetic {@code {test: true}} event with no {@code workItemId} also
     * fanned out to workflow automation and knowledge ingestion. {@link NotificationChannelService} now
     * depends on {@link NotificationDeliveryService} only, so a spurious {@code WorkflowRun} or knowledge
     * submission from this button is structurally impossible, not just unasserted.
     */
    @Test
    void testChannelDeliversDirectlyWithoutFanningOutToWorkflowsOrKnowledge() {
        NotificationChannelConfig config = buildConfig(EventType.WORK_ITEM_STATUS_CHANGED);
        when(channelConfigRepository.findByProjectIdAndEventType("proj-1", EventType.WORK_ITEM_STATUS_CHANGED))
                .thenReturn(Optional.of(config));

        service.testChannel("proj-1", "WORK_ITEM_STATUS_CHANGED", adminUser);

        ArgumentCaptor<NotificationMessage> captor = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationDeliveryService).deliver(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsEntry("test", "true").doesNotContainKey("workItemId");
        verifyNoMoreInteractions(notificationDeliveryService);
    }

    private NotificationChannelConfig buildConfig(EventType eventType) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setProjectId("proj-1");
        config.setEventType(eventType);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl("https://discord.com/api/webhooks/123/abc");
        config.setEnabled(true);
        return config;
    }

    private NotificationChannelRequest buildRequest(String webhookUrl) {
        NotificationChannelRequest req = new NotificationChannelRequest();
        req.setProvider(NotificationChannelRequest.ProviderEnum.DISCORD);
        req.setWebhookUrl(webhookUrl);
        req.setEnabled(true);
        return req;
    }
}
