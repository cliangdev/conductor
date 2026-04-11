package com.conductor.service;

import com.conductor.entity.MemberRole;
import com.conductor.entity.NotificationGroupConfig;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.NotificationGroupRequest;
import com.conductor.notification.ChannelGroup;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.ProviderType;
import com.conductor.repository.NotificationGroupConfigRepository;
import com.conductor.repository.ProjectMemberRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationGroupServiceTest {

    @Mock
    private NotificationGroupConfigRepository groupConfigRepository;

    @Mock
    private NotificationDispatcher notificationDispatcher;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @InjectMocks
    private NotificationGroupService service;

    private User adminUser;
    private ProjectMember adminMember;

    @BeforeEach
    void setUp() {
        adminUser = new User();
        adminUser.setId("user-1");

        adminMember = new ProjectMember();
        adminMember.setRole(MemberRole.ADMIN);

        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1"))
                .thenReturn(Optional.of(adminMember));
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
        adminMember.setRole(MemberRole.REVIEWER);

        assertThatThrownBy(() -> service.getGroups("proj-1", adminUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void upsertGroupCreatesNewConfig() {
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.empty());
        when(groupConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationGroupRequest req = buildRequest("https://discord.com/test", List.of("ISSUE_SUBMITTED"));
        NotificationGroupService.UpsertResult result = service.upsertGroup("proj-1", "ISSUES", req, adminUser);

        assertThat(result.isNew()).isTrue();
        ArgumentCaptor<NotificationGroupConfig> captor = ArgumentCaptor.forClass(NotificationGroupConfig.class);
        verify(groupConfigRepository).save(captor.capture());
        NotificationGroupConfig saved = captor.getValue();
        assertThat(saved.getChannelGroup()).isEqualTo(ChannelGroup.ISSUES);
        assertThat(saved.getWebhookUrl()).isEqualTo("https://discord.com/test");
        assertThat(saved.getEnabledEventTypes()).containsExactly("ISSUE_SUBMITTED");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void upsertGroupUpdatesExistingConfig() {
        NotificationGroupConfig existing = buildConfig(ChannelGroup.ISSUES);
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.of(existing));
        when(groupConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NotificationGroupRequest req = buildRequest("https://discord.com/updated",
                List.of("ISSUE_SUBMITTED", "ISSUE_APPROVED"));
        NotificationGroupService.UpsertResult result = service.upsertGroup("proj-1", "ISSUES", req, adminUser);

        assertThat(result.isNew()).isFalse();
        assertThat(result.config().getWebhookUrl()).isEqualTo("https://discord.com/updated");
        assertThat(result.config().getEnabledEventTypes()).containsExactlyInAnyOrder("ISSUE_SUBMITTED", "ISSUE_APPROVED");
    }

    @Test
    void upsertGroupThrowsForBlankWebhookUrl() {
        NotificationGroupRequest req = buildRequest("  ", List.of("ISSUE_SUBMITTED"));

        assertThatThrownBy(() -> service.upsertGroup("proj-1", "ISSUES", req, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("webhookUrl");
    }

    @Test
    void upsertGroupThrowsForEventTypeNotInGroup() {
        NotificationGroupRequest req = buildRequest("https://discord.com/test",
                List.of("ISSUE_SUBMITTED"));

        assertThatThrownBy(() -> service.upsertGroup("proj-1", "MEMBERS", req, adminUser))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ISSUE_SUBMITTED");
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
        config.setEnabledEventTypes(Set.of("ISSUE_SUBMITTED"));
        when(groupConfigRepository.findByProjectIdAndChannelGroup("proj-1", ChannelGroup.ISSUES))
                .thenReturn(Optional.of(config));

        var response = service.testGroup("proj-1", "ISSUES", adminUser);

        assertThat(response.getSuccess()).isTrue();
        verify(notificationDispatcher).dispatch(any());
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
