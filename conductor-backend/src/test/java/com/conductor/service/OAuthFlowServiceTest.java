package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.IntegrationOAuthState;
import com.conductor.exception.BusinessException;
import com.conductor.integration.AuthType;
import com.conductor.repository.IntegrationOAuthStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthFlowServiceTest {

    @Mock
    private IntegrationOAuthStateRepository oAuthStateRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private RestTemplate restTemplate;

    private OAuthFlowService service;

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTOR_ID = "gcp-billing";
    private static final String REDIRECT_URI = "http://localhost:8080/api/v1/oauth/callback";

    @BeforeEach
    void setUp() {
        service = new OAuthFlowService(oAuthStateRepository, connectionService, new ObjectMapper());
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "googleClientId", "test-client-id");
        ReflectionTestUtils.setField(service, "googleClientSecret", "test-client-secret");
        ReflectionTestUtils.setField(service, "frontendUrl", "http://localhost:3000");
    }

    @Test
    void buildAuthorizationUrlPersistsStateAndReturnsUrl() {
        String url = service.buildAuthorizationUrl(PROJECT_ID, CONNECTOR_ID, REDIRECT_URI);

        ArgumentCaptor<IntegrationOAuthState> captor = ArgumentCaptor.forClass(IntegrationOAuthState.class);
        verify(oAuthStateRepository).deleteByExpiresAtBefore(any(OffsetDateTime.class));
        verify(oAuthStateRepository).save(captor.capture());

        IntegrationOAuthState saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(saved.getConnectorId()).isEqualTo(CONNECTOR_ID);
        assertThat(saved.getState()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfter(OffsetDateTime.now());

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth");
        assertThat(url).contains("client_id=test-client-id");
        assertThat(url).contains("state=" + saved.getState());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void handleCallbackWithValidStateExchangesCodeAndStoresCredentials() {
        String state = "validstate";
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState(state);
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId(CONNECTOR_ID);
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        when(oAuthStateRepository.findById(state)).thenReturn(Optional.of(oauthState));

        Connection conn = new Connection();
        conn.setId("conn-1");
        conn.setProjectId(PROJECT_ID);
        conn.setConnectorId(CONNECTOR_ID);
        when(connectionService.getOrCreateSingle(PROJECT_ID, CONNECTOR_ID, AuthType.OAUTH2)).thenReturn(conn);

        Map<String, Object> tokenResponse = Map.of(
                "access_token", "access-123",
                "refresh_token", "refresh-456",
                "expires_in", 3600);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(tokenResponse));

        String redirect = service.handleCallback("auth-code", state, REDIRECT_URI);

        verify(oAuthStateRepository).delete(oauthState);
        verify(connectionService).storeTokens(
                eq(conn), eq("access-123"), eq("refresh-456"), any(OffsetDateTime.class));
        assertThat(redirect).isEqualTo(
                "http://localhost:3000/app/projects/proj-1/integrations/gcp-billing");
    }

    @Test
    void handleCallbackWithUnknownStateThrowsBadRequest() {
        when(oAuthStateRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handleCallback("auth-code", "missing", REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or expired OAuth state");

        verify(connectionService, never()).storeTokens(any(), anyString(), anyString(), any());
    }

    @Test
    void handleCallbackWithExpiredStateThrowsBadRequestAndDeletesState() {
        String state = "expiredstate";
        IntegrationOAuthState oauthState = new IntegrationOAuthState();
        oauthState.setState(state);
        oauthState.setProjectId(PROJECT_ID);
        oauthState.setConnectorId(CONNECTOR_ID);
        oauthState.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(oAuthStateRepository.findById(state)).thenReturn(Optional.of(oauthState));

        assertThatThrownBy(() -> service.handleCallback("auth-code", state, REDIRECT_URI))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(oAuthStateRepository).delete(oauthState);
        verify(connectionService, never()).storeTokens(any(), anyString(), anyString(), any());
    }
}
