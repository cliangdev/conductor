package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.integration.AuthType;
import com.conductor.repository.ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveConnectionResolverTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String CONNECTOR_ID = "github";

    @Mock private ConnectionRepository connectionRepository;

    private ActiveConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ActiveConnectionResolver(connectionRepository);
    }

    private Connection connection(String id, String authType, String status) {
        return connection(id, authType, status, PROJECT_ID);
    }

    private Connection connection(String id, String authType, String status, String projectId) {
        Connection c = new Connection();
        c.setId(id);
        c.setProjectId(projectId);
        c.setConnectorId(CONNECTOR_ID);
        c.setAuthType(authType);
        c.setStatus(status);
        return c;
    }

    @Test
    void resolve_prefersActivePatOverActiveApp_whenBothPresent() {
        Connection app = connection("conn-app", AuthType.APP.name(), "ACTIVE");
        Connection pat = connection("conn-pat", AuthType.PAT.name(), "ACTIVE");
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(List.of(app, pat));

        Optional<Connection> resolved = resolver.resolve(PROJECT_ID, CONNECTOR_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo("conn-pat");
    }

    @Test
    void resolve_fallsBackToFirstActiveConnection_whenNoPatExists() {
        Connection app = connection("conn-app", AuthType.APP.name(), "ACTIVE");
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(List.of(app));

        Optional<Connection> resolved = resolver.resolve(PROJECT_ID, CONNECTOR_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo("conn-app");
    }

    @Test
    void resolve_ignoresInactivePat_fallsBackToActiveApp() {
        Connection app = connection("conn-app", AuthType.APP.name(), "ACTIVE");
        Connection inactivePat = connection("conn-pat", AuthType.PAT.name(), "REVOKED");
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(List.of(app, inactivePat));

        Optional<Connection> resolved = resolver.resolve(PROJECT_ID, CONNECTOR_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo("conn-app");
    }

    @Test
    void resolve_returnsEmpty_whenNoActiveConnectionsExist() {
        when(connectionRepository.findByProjectIdAndConnectorId(PROJECT_ID, CONNECTOR_ID))
                .thenReturn(List.of());

        assertThat(resolver.resolve(PROJECT_ID, CONNECTOR_ID)).isEmpty();
    }

    @Test
    void resolveById_returnsNamedActiveConnection_withinProject() {
        Connection named = connection("conn-b", AuthType.PAT.name(), "ACTIVE");
        when(connectionRepository.findById("conn-b")).thenReturn(Optional.of(named));

        Optional<Connection> resolved = resolver.resolveById(PROJECT_ID, "conn-b");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getId()).isEqualTo("conn-b");
    }

    @Test
    void resolveById_returnsEmpty_forConnectionOwnedByAnotherProject() {
        Connection foreign = connection("conn-foreign", AuthType.PAT.name(), "ACTIVE", "proj-2");
        when(connectionRepository.findById("conn-foreign")).thenReturn(Optional.of(foreign));

        assertThat(resolver.resolveById(PROJECT_ID, "conn-foreign")).isEmpty();
    }

    @Test
    void resolveById_returnsEmpty_forNonActiveConnection() {
        Connection revoked = connection("conn-revoked", AuthType.PAT.name(), "REVOKED");
        when(connectionRepository.findById("conn-revoked")).thenReturn(Optional.of(revoked));

        assertThat(resolver.resolveById(PROJECT_ID, "conn-revoked")).isEmpty();
    }

    @Test
    void resolveById_returnsEmpty_forUnknownConnectionId() {
        when(connectionRepository.findById("conn-missing")).thenReturn(Optional.empty());

        assertThat(resolver.resolveById(PROJECT_ID, "conn-missing")).isEmpty();
    }

    @Test
    void resolveById_returnsEmpty_forBlankConnectionId_withoutQueryingRepository() {
        assertThat(resolver.resolveById(PROJECT_ID, "  ")).isEmpty();
        assertThat(resolver.resolveById(PROJECT_ID, null)).isEmpty();
        verifyNoInteractions(connectionRepository);
    }
}
