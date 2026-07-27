package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestSpec;
import com.conductor.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reconciles a connector's declared {@code ingest[]} entries (see {@link IngestSpec}) into
 * {@link ConnectorFeed} rows for one connection. Idempotent: an already-provisioned
 * {@code (connectionId, ingestId)} pair is left untouched (never re-seeded, never duplicated — the
 * unique constraint backs this up, but this check avoids relying on catching the violation). A
 * connector with an empty {@code ingest[]} list (all six pre-existing connectors, as of this feature)
 * gets zero rows created — provisioning is opt-in per connector via its tool-spec JSON, not automatic.
 *
 * <p>Called next to the three {@code computeAndStoreToolMetadata} call sites in
 * {@code ConnectionService} (create, token refresh, config update) — the same three moments a
 * connection's tool metadata can change, so a re-authenticated or reconfigured connection also
 * re-reconciles its feeds. {@link #reconcileExisting()} covers connections created before this
 * feature shipped, running once at startup.
 */
@Component
public class ConnectorFeedProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ConnectorFeedProvisioner.class);

    private final ConnectorFeedRepository feedRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectionRepository connectionRepository;

    public ConnectorFeedProvisioner(ConnectorFeedRepository feedRepository,
                                    @Lazy ConnectorRegistry connectorRegistry,
                                    ConnectionRepository connectionRepository) {
        this.feedRepository = feedRepository;
        this.connectorRegistry = connectorRegistry;
        this.connectionRepository = connectionRepository;
    }

    /** Reconciles one connection's feeds against its connector's current {@code ingest[]} declarations. */
    public void reconcile(Connection connection) {
        Connector connector = connectorRegistry.getById(connection.getConnectorId()).orElse(null);
        if (connector == null) {
            return;
        }
        List<IngestSpec> declared = connector.getToolSpec().ingest();
        if (declared.isEmpty()) {
            return;
        }
        for (IngestSpec spec : declared) {
            if (feedRepository.findByConnectionIdAndIngestId(connection.getId(), spec.id()).isPresent()) {
                continue;
            }
            ConnectorFeed feed = new ConnectorFeed();
            feed.setProjectId(connection.getProjectId());
            feed.setConnectionId(connection.getId());
            feed.setConnectorId(connection.getConnectorId());
            feed.setIngestId(spec.id());
            feed.setMode(spec.mode());
            feed.setIntervalMinutes(spec.defaultIntervalMinutes());
            feedRepository.save(feed);
            log.info("Provisioned connector_feed for connection={} ingest={}", connection.getId(), spec.id());
        }
    }

    /**
     * Catch-up path for connections that existed before this feature shipped: reconciles every
     * connection in the database once the application is fully up. Cheap and safe to run every boot —
     * the per-connection reconcile above is already a no-op once a feed exists.
     *
     * <p>Deliberately {@link ApplicationReadyEvent}, not {@code @PostConstruct}: this method's first
     * call into {@link ConnectorRegistry} forces its real (non-proxy) construction, which requires
     * every {@link Connector} bean to exist — and at least one ({@code GitHubConnector}) depends back
     * on {@code ConnectionService}, which depends on this class. Triggering that resolution while the
     * context is still mid-refresh (as {@code @PostConstruct} would) risks a genuine
     * {@code BeanCurrentlyInCreationException} depending on bean creation order; firing only after the
     * whole context has finished refreshing sidesteps it entirely.
     */
    @EventListener(ApplicationReadyEvent.class)
    void reconcileExisting() {
        List<Connection> connections = connectionRepository.findAll();
        connections.forEach(this::reconcile);
        if (!connections.isEmpty()) {
            log.info("Reconciled connector feeds for {} existing connections", connections.size());
        }
    }
}
