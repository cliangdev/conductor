package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.DecryptedCredentials;
import com.conductor.integration.OAuth2Connector;
import com.conductor.repository.ConnectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuthRevocationServiceTest {

    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
    private final OAuth2Connector youtube = mock(OAuth2Connector.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
    private final OAuthRevocationService service =
            new OAuthRevocationService(connectionService, connectionRepository, registry, restTemplate);

    @BeforeEach
    void setUp() {
        when(registry.findOAuth2("youtube")).thenReturn(Optional.of(youtube));
        when(youtube.revocationUrl()).thenReturn(Optional.of(REVOKE_URL));
        when(youtube.accountIdentityConfigKey()).thenReturn(Optional.of("channelId"));
        when(connectionRepository.findByConnectorIdAndConfigValue(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Connection connection(String id, String connectorId) {
        Connection c = new Connection();
        c.setId(id);
        c.setConnectorId(connectorId);
        return c;
    }

    private void tokens(Connection c, String access, String refresh) {
        when(connectionService.decrypt(c)).thenReturn(
                new DecryptedCredentials(access, refresh, null, Map.of("channelId", "UC123")));
    }

    @Test
    void postsTheRefreshTokenAsAFormBody() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", "refresh-tok");
        server.expect(once(), requestTo(REVOKE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(content().string("token=refresh-tok"))
                .andRespond(withSuccess());

        service.revokeAfterCommit(c);

        server.verify();
    }

    @Test
    void fallsBackToTheAccessTokenWithoutARefreshToken() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", null);
        server.expect(once(), requestTo(REVOKE_URL))
                .andExpect(content().string("token=access-tok"))
                .andRespond(withSuccess());

        service.revokeAfterCommit(c);

        server.verify();
    }

    @Test
    void callsOnlyAfterCommitWhenATransactionIsActive() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", "refresh-tok");
        TransactionSynchronizationManager.initSynchronization();
        server.expect(never(), requestTo(REVOKE_URL));

        service.revokeAfterCommit(c);
        server.verify();

        server.reset();
        server.expect(once(), requestTo(REVOKE_URL)).andRespond(withSuccess());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        server.verify();
    }

    @Test
    void aConnectorWithoutARevocationUrlMakesNoCall() {
        OAuth2Connector meta = mock(OAuth2Connector.class);
        when(meta.revocationUrl()).thenReturn(Optional.empty());
        when(registry.findOAuth2("meta")).thenReturn(Optional.of(meta));
        server.expect(never(), requestTo(REVOKE_URL));

        service.revokeAfterCommit(connection("conn-2", "meta"));

        server.verify();
    }

    @Test
    void anotherConnectionToTheSameChannelMeansNoCall() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", "refresh-tok");
        Connection other = connection("conn-other", "youtube");
        when(connectionRepository.findByConnectorIdAndConfigValue("youtube", "channelId", "UC123"))
                .thenReturn(List.of(c, other));
        server.expect(never(), requestTo(REVOKE_URL));

        service.revokeAfterCommit(c);

        server.verify();
    }

    @Test
    void aServerErrorNeverThrows() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", "refresh-tok");
        server.expect(once(), requestTo(REVOKE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatCode(() -> service.revokeAfterCommit(c)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void aNetworkErrorNeverThrows() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", "refresh-tok");
        server.expect(once(), requestTo(REVOKE_URL)).andRespond(request -> {
            throw new java.io.IOException("connection reset");
        });

        assertThatCode(() -> service.revokeAfterCommit(c)).doesNotThrowAnyException();
    }

    @Test
    void anInvalidTokenResponseNeverThrows() {
        Connection c = connection("conn-1", "youtube");
        tokens(c, "access-tok", "refresh-tok");
        server.expect(once(), requestTo(REVOKE_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_token\"}"));

        assertThatCode(() -> service.revokeAfterCommit(c)).doesNotThrowAnyException();
        server.verify();
    }
}
