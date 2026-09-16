package com.conductor.integration.connector.marketing;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.ProjectSettings;
import com.conductor.integration.AuthType;
import com.conductor.integration.ingest.ConnectorFeedProvisioner;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain-object coverage (no Spring) for {@link MarketingInsightsFeedProvisioner}: {@link
 * ConnectionService} and {@link ConnectorFeedProvisioner} are mocked, since the real classes'
 * behavior (single-instance connect races, feed reconciliation) is already covered by their own
 * tests — this test is only about which projects this provisioner decides to touch.
 */
class MarketingInsightsFeedProvisionerTest {

    private ProjectSettingsRepository projectSettingsRepository;
    private PostPublishTargetRepository targetRepository;
    private ConnectionService connectionService;
    private ConnectorFeedProvisioner connectorFeedProvisioner;
    private MarketingInsightsFeedProvisioner provisioner;

    @BeforeEach
    void setUp() {
        projectSettingsRepository = mock(ProjectSettingsRepository.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        connectionService = mock(ConnectionService.class);
        connectorFeedProvisioner = mock(ConnectorFeedProvisioner.class);
        provisioner = new MarketingInsightsFeedProvisioner(
                projectSettingsRepository, targetRepository, connectionService, connectorFeedProvisioner);
    }

    private static ProjectSettings settingsFor(String projectId) {
        ProjectSettings s = new ProjectSettings();
        s.setProjectId(projectId);
        s.setKnowledgeEnabled(true);
        return s;
    }

    @Test
    void createsTheConnectionAndFeedOnceNotTwice() {
        when(projectSettingsRepository.findAllByKnowledgeEnabledTrue()).thenReturn(List.of(settingsFor("proj-1")));
        when(targetRepository.existsByWorkItem_Project_IdAndState("proj-1", PostPublishTargetState.PUBLISHED))
                .thenReturn(true);
        Connection connection = new Connection();
        connection.setId("conn-1");

        // First tick: no connection yet.
        when(connectionService.findSingle("proj-1", MarketingInsightsConnector.ID)).thenReturn(Optional.empty());
        when(connectionService.getOrCreateSingle("proj-1", MarketingInsightsConnector.ID, AuthType.NONE))
                .thenReturn(connection);
        provisioner.provisionEligibleProjects();

        verify(connectionService).updateLabel(connection, "Marketing insights");
        verify(connectorFeedProvisioner, times(1)).reconcile(connection);

        // Second tick: connection already exists — must not re-label, but reconcile stays idempotent.
        when(connectionService.findSingle("proj-1", MarketingInsightsConnector.ID)).thenReturn(Optional.of(connection));
        provisioner.provisionEligibleProjects();

        verify(connectionService, times(1)).updateLabel(connection, "Marketing insights"); // still only once
        verify(connectorFeedProvisioner, times(2)).reconcile(connection); // reconcile itself is idempotent
    }

    @Test
    void skipsProjectsWithoutAPublishedTarget() {
        when(projectSettingsRepository.findAllByKnowledgeEnabledTrue()).thenReturn(List.of(settingsFor("proj-2")));
        when(targetRepository.existsByWorkItem_Project_IdAndState("proj-2", PostPublishTargetState.PUBLISHED))
                .thenReturn(false);

        provisioner.provisionEligibleProjects();

        verify(connectionService, never()).getOrCreateSingle(anyString(), anyString(), any(AuthType.class));
    }

    @Test
    void skipsProjectsThatNeverAppearBecauseKnowledgeIsDisabled() {
        // knowledge-disabled projects are filtered by the repository query itself, so an empty
        // result means "nothing eligible" without this provisioner needing its own knowledge check.
        when(projectSettingsRepository.findAllByKnowledgeEnabledTrue()).thenReturn(List.of());

        provisioner.provisionEligibleProjects();

        verify(connectionService, never()).getOrCreateSingle(anyString(), anyString(), any(AuthType.class));
    }
}
