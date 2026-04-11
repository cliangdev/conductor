package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.NotificationChannelConfig;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.generated.model.NotificationTestResponse;
import com.conductor.notification.EventType;
import com.conductor.notification.ProviderType;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.NotificationChannelService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationChannelController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class NotificationChannelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationChannelService notificationChannelService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProjectApiKeyRepository projectApiKeyRepository;

    @MockitoBean
    private UserApiKeyRepository userApiKeyRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId("user-id-123");
        testUser.setEmail("admin@example.com");

        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-id-123");
        when(userRepository.findById("user-id-123")).thenReturn(Optional.of(testUser));
    }

    private NotificationChannelConfig buildConfig(EventType eventType) {
        NotificationChannelConfig config = new NotificationChannelConfig();
        config.setProjectId("proj-1");
        config.setEventType(eventType);
        config.setProvider(ProviderType.DISCORD);
        config.setWebhookUrl("https://discord.com/api/webhooks/123/abc");
        config.setEnabled(true);
        config.setCreatedAt(OffsetDateTime.now());
        config.setUpdatedAt(OffsetDateTime.now());
        return config;
    }

    @Test
    void getNotificationChannelsReturns200WithList() throws Exception {
        NotificationChannelConfig config = buildConfig(EventType.ISSUE_SUBMITTED);
        when(notificationChannelService.getChannels(eq("proj-1"), any(User.class)))
                .thenReturn(List.of(config));

        mockMvc.perform(get("/api/v1/projects/proj-1/notifications/channels")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventType").value("ISSUE_SUBMITTED"))
                .andExpect(jsonPath("$[0].provider").value("DISCORD"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void getNotificationChannelsReturns403ForNonAdmin() throws Exception {
        when(notificationChannelService.getChannels(eq("proj-1"), any(User.class)))
                .thenThrow(new ForbiddenException("Only ADMIN can manage notification channels"));

        mockMvc.perform(get("/api/v1/projects/proj-1/notifications/channels")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getNotificationChannelsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects/proj-1/notifications/channels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upsertNotificationChannelReturns201OnCreate() throws Exception {
        NotificationChannelConfig config = buildConfig(EventType.ISSUE_SUBMITTED);
        NotificationChannelService.UpsertResult result = new NotificationChannelService.UpsertResult(config, true);

        when(notificationChannelService.upsertChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(), any(User.class)))
                .thenReturn(result);

        mockMvc.perform(put("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"DISCORD\",\"webhookUrl\":\"https://discord.com/api/webhooks/123/abc\",\"enabled\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("ISSUE_SUBMITTED"))
                .andExpect(jsonPath("$.provider").value("DISCORD"));
    }

    @Test
    void upsertNotificationChannelReturns200OnUpdate() throws Exception {
        NotificationChannelConfig config = buildConfig(EventType.ISSUE_SUBMITTED);
        NotificationChannelService.UpsertResult result = new NotificationChannelService.UpsertResult(config, false);

        when(notificationChannelService.upsertChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(), any(User.class)))
                .thenReturn(result);

        mockMvc.perform(put("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"DISCORD\",\"webhookUrl\":\"https://discord.com/api/webhooks/123/abc\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("ISSUE_SUBMITTED"));
    }

    @Test
    void upsertNotificationChannelReturns400ForInvalidEventType() throws Exception {
        when(notificationChannelService.upsertChannel(eq("proj-1"), eq("INVALID_EVENT"), any(), any(User.class)))
                .thenThrow(new BusinessException("Invalid event type: INVALID_EVENT"));

        mockMvc.perform(put("/api/v1/projects/proj-1/notifications/channels/INVALID_EVENT")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"DISCORD\",\"webhookUrl\":\"https://discord.com/api/webhooks/123/abc\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertNotificationChannelReturns400ForBlankWebhookUrl() throws Exception {
        when(notificationChannelService.upsertChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(), any(User.class)))
                .thenThrow(new BusinessException("webhookUrl must not be blank"));

        mockMvc.perform(put("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"DISCORD\",\"webhookUrl\":\"  \",\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertNotificationChannelReturns403ForNonAdmin() throws Exception {
        when(notificationChannelService.upsertChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(), any(User.class)))
                .thenThrow(new ForbiddenException("Only ADMIN can manage notification channels"));

        mockMvc.perform(put("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"DISCORD\",\"webhookUrl\":\"https://discord.com/api/webhooks/123/abc\",\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteNotificationChannelReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteNotificationChannelReturns404WhenNotFound() throws Exception {
        doThrow(new EntityNotFoundException("No channel configured for event type ISSUE_SUBMITTED"))
                .when(notificationChannelService).deleteChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(User.class));

        mockMvc.perform(delete("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteNotificationChannelReturns403ForNonAdmin() throws Exception {
        doThrow(new ForbiddenException("Only ADMIN can manage notification channels"))
                .when(notificationChannelService).deleteChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(User.class));

        mockMvc.perform(delete("/api/v1/projects/proj-1/notifications/channels/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void testNotificationChannelReturnsSuccessWhenChannelExists() throws Exception {
        NotificationTestResponse testResponse = new NotificationTestResponse();
        testResponse.setSuccess(true);
        testResponse.setMessage("Test notification sent");

        when(notificationChannelService.testChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(User.class)))
                .thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/projects/proj-1/notifications/test/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Test notification sent"));
    }

    @Test
    void testNotificationChannelReturnsFailureWhenNoChannelConfigured() throws Exception {
        NotificationTestResponse testResponse = new NotificationTestResponse();
        testResponse.setSuccess(false);
        testResponse.setMessage("No channel configured for event type ISSUE_SUBMITTED");

        when(notificationChannelService.testChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(User.class)))
                .thenReturn(testResponse);

        mockMvc.perform(post("/api/v1/projects/proj-1/notifications/test/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("No channel configured for event type ISSUE_SUBMITTED"));
    }

    @Test
    void testNotificationChannelReturns403ForNonAdmin() throws Exception {
        when(notificationChannelService.testChannel(eq("proj-1"), eq("ISSUE_SUBMITTED"), any(User.class)))
                .thenThrow(new ForbiddenException("Only ADMIN can manage notification channels"));

        mockMvc.perform(post("/api/v1/projects/proj-1/notifications/test/ISSUE_SUBMITTED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }
}
