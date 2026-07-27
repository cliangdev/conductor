package com.conductor.integration.ingest;

import com.conductor.disposition.Disposition;
import com.conductor.disposition.DispositionPolicy;
import com.conductor.disposition.DispositionPolicyCache;
import com.conductor.disposition.DispositionPolicyRepository;
import com.conductor.entity.Connection;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.repository.ConnectionRepository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorFeedProvisionerTest {

    private final ConnectorFeedRepository feedRepository = mock(ConnectorFeedRepository.class);
    private final ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final DispositionPolicyRepository dispositionPolicyRepository = mock(DispositionPolicyRepository.class);
    private final DispositionPolicyCache dispositionPolicyCache = mock(DispositionPolicyCache.class);

    private final ConnectorFeedProvisioner provisioner = new ConnectorFeedProvisioner(
            feedRepository, connectorRegistry, connectionRepository, dispositionPolicyRepository, dispositionPolicyCache);

    /** Real (non-mock) {@link Connector} -- {@code getToolSpec()} is a default interface method, and
     *  Mockito can't stub default methods cleanly (see FeedPullServiceTest for the same workaround). */
    private static final class FakeConnector implements Connector {
        private final List<IngestSpec> ingest;

        FakeConnector(List<IngestSpec> ingest) {
            this.ingest = ingest;
        }

        @Override public String getId() { return "gsc"; }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("gsc", "Google Search Console", ConnectorCategory.ANALYTICS, "desc", "GSC");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }

        @Override
        public IntegrationToolSpec getToolSpec() {
            return new IntegrationToolSpec("gsc", List.of(), List.of(), ingest);
        }
    }

    private static Connection connection(String id, String connectorId) {
        Connection c = new Connection();
        c.setId(id);
        c.setProjectId("proj-1");
        c.setConnectorId(connectorId);
        return c;
    }

    private static IngestSpec spec(String id) {
        return new IngestSpec(id, "label", "desc", IngestMode.SNAPSHOT, "search_analytics",
                "metrics.digest.{connector}.{ingest}", 10080, null, null, null, null);
    }

    private static IngestSpec specWithDisposition(String id, String suggestedDisposition, String suggestedDomain) {
        return new IngestSpec(id, "label", "desc", IngestMode.SNAPSHOT, "search_analytics",
                "metrics.digest.{connector}.{ingest}", 10080, null, suggestedDisposition, suggestedDomain, null);
    }

    @Test
    void createsAFeedForEachDeclaredIngestNotAlreadyProvisioned() {
        Connection connection = connection("conn-1", "gsc");
        when(connectorRegistry.getById("gsc"))
                .thenReturn(Optional.of(new FakeConnector(List.of(spec("weekly")))));
        when(feedRepository.findByConnectionIdAndIngestId("conn-1", "weekly")).thenReturn(Optional.empty());

        provisioner.reconcile(connection);

        verify(feedRepository).save(argThatFeed("conn-1", "weekly"));
    }

    @Test
    void isIdempotentAndSkipsAnAlreadyProvisionedIngestId() {
        Connection connection = connection("conn-1", "gsc");
        when(connectorRegistry.getById("gsc"))
                .thenReturn(Optional.of(new FakeConnector(List.of(spec("weekly")))));
        when(feedRepository.findByConnectionIdAndIngestId("conn-1", "weekly"))
                .thenReturn(Optional.of(new ConnectorFeed()));

        provisioner.reconcile(connection);

        verify(feedRepository, never()).save(any());
    }

    @Test
    void createsNothingForAConnectorWithAnEmptyIngestList() {
        Connection connection = connection("conn-1", "posthog");
        when(connectorRegistry.getById("posthog")).thenReturn(Optional.of(new FakeConnector(List.of())));

        provisioner.reconcile(connection);

        verify(feedRepository, never()).save(any());
        verify(feedRepository, never()).findByConnectionIdAndIngestId(any(), any());
    }

    @Test
    void doesNothingWhenTheConnectorIsUnknown() {
        Connection connection = connection("conn-1", "ghost");
        when(connectorRegistry.getById("ghost")).thenReturn(Optional.empty());

        provisioner.reconcile(connection);

        verify(feedRepository, never()).save(any());
    }

    @Test
    void reconcileExistingReconcilesEveryConnectionInTheDatabase() {
        Connection c1 = connection("conn-1", "gsc");
        Connection c2 = connection("conn-2", "gsc");
        when(connectionRepository.findAll()).thenReturn(List.of(c1, c2));
        when(connectorRegistry.getById("gsc"))
                .thenReturn(Optional.of(new FakeConnector(List.of(spec("weekly")))));
        when(feedRepository.findByConnectionIdAndIngestId(any(), any())).thenReturn(Optional.empty());

        provisioner.reconcileExisting();

        verify(feedRepository).save(argThatFeed("conn-1", "weekly"));
        verify(feedRepository).save(argThatFeed("conn-2", "weekly"));
        verify(feedRepository, times(2)).save(any());
    }

    // ---- disposition policy seeding ----

    @Test
    void seedsDispositionPolicyWhenSuggestedDispositionPresent() {
        Connection connection = connection("conn-1", "gsc");
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(
                new FakeConnector(List.of(specWithDisposition("weekly", "KNOWLEDGE", "marketing")))));
        when(feedRepository.findByConnectionIdAndIngestId("conn-1", "weekly")).thenReturn(Optional.empty());
        when(dispositionPolicyRepository.findByProjectIdAndSignalTypeAndDisposition(
                "proj-1", "metrics.digest.gsc.weekly", Disposition.KNOWLEDGE)).thenReturn(Optional.empty());

        provisioner.reconcile(connection);

        org.mockito.ArgumentCaptor<DispositionPolicy> captor = org.mockito.ArgumentCaptor.forClass(DispositionPolicy.class);
        verify(dispositionPolicyRepository).save(captor.capture());
        DispositionPolicy saved = captor.getValue();
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getSignalType()).isEqualTo("metrics.digest.gsc.weekly");
        assertThat(saved.getDisposition()).isEqualTo(Disposition.KNOWLEDGE);
        assertThat(saved.getConfig()).containsEntry("domain", "marketing");
        verify(dispositionPolicyCache).invalidate("proj-1");
    }

    @Test
    void skipsDispositionPolicySeedingWhenSuggestedDispositionAbsent() {
        Connection connection = connection("conn-1", "gsc");
        when(connectorRegistry.getById("gsc"))
                .thenReturn(Optional.of(new FakeConnector(List.of(spec("weekly")))));
        when(feedRepository.findByConnectionIdAndIngestId("conn-1", "weekly")).thenReturn(Optional.empty());

        provisioner.reconcile(connection);

        verify(dispositionPolicyRepository, never()).save(any());
        verify(dispositionPolicyCache, never()).invalidate(any());
    }

    @Test
    void isIdempotentAndSkipsAnAlreadySeededDispositionPolicy() {
        Connection connection = connection("conn-1", "gsc");
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(
                new FakeConnector(List.of(specWithDisposition("weekly", "KNOWLEDGE", "marketing")))));
        when(feedRepository.findByConnectionIdAndIngestId("conn-1", "weekly")).thenReturn(Optional.empty());
        when(dispositionPolicyRepository.findByProjectIdAndSignalTypeAndDisposition(
                "proj-1", "metrics.digest.gsc.weekly", Disposition.KNOWLEDGE))
                .thenReturn(Optional.of(new DispositionPolicy()));

        provisioner.reconcile(connection);

        verify(dispositionPolicyRepository, never()).save(any());
    }

    @Test
    void unknownSuggestedDispositionString_skipsSeedingWithoutThrowing() {
        Connection connection = connection("conn-1", "gsc");
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(
                new FakeConnector(List.of(specWithDisposition("weekly", "NOT_A_REAL_DISPOSITION", null)))));
        when(feedRepository.findByConnectionIdAndIngestId("conn-1", "weekly")).thenReturn(Optional.empty());

        provisioner.reconcile(connection);

        verify(dispositionPolicyRepository, never()).save(any());
    }

    private static ConnectorFeed argThatFeed(String connectionId, String ingestId) {
        return org.mockito.ArgumentMatchers.argThat(f ->
                f != null && connectionId.equals(f.getConnectionId()) && ingestId.equals(f.getIngestId()));
    }
}
