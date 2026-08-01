package com.conductor.integration.ingest;

import com.conductor.disposition.Disposition;
import com.conductor.disposition.DispositionPolicy;
import com.conductor.disposition.DispositionPolicyCache;
import com.conductor.disposition.DispositionPolicyRepository;
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
import java.util.Map;

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
    private final DispositionPolicyRepository dispositionPolicyRepository;
    private final DispositionPolicyCache dispositionPolicyCache;

    public ConnectorFeedProvisioner(ConnectorFeedRepository feedRepository,
                                    @Lazy ConnectorRegistry connectorRegistry,
                                    ConnectionRepository connectionRepository,
                                    DispositionPolicyRepository dispositionPolicyRepository,
                                    DispositionPolicyCache dispositionPolicyCache) {
        this.feedRepository = feedRepository;
        this.connectorRegistry = connectorRegistry;
        this.connectionRepository = connectionRepository;
        this.dispositionPolicyRepository = dispositionPolicyRepository;
        this.dispositionPolicyCache = dispositionPolicyCache;
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
            seedDispositionPolicy(connection, spec);
        }
    }

    /**
     * Seeds one {@link DispositionPolicy} row from {@link IngestSpec#suggestedDisposition()}/{@link
     * IngestSpec#suggestedDomain()} -- once, right here, at first-provisioning time only. This is the
     * ONLY place that ever reads those two {@code IngestSpec} fields; nothing at pull or digest time
     * reads them again (see {@code IngestSpec}'s javadoc on why treating them as a second, competing
     * source of runtime policy would let a feed disagree with itself depending on which copy a caller
     * happened to read). A missing/blank {@code suggestedDisposition} means the connector author left
     * routing to project defaults -- nothing to seed. An existing row for the same {@code
     * (projectId, signalType, disposition)} is left untouched, matching every other seed method in this
     * feature (idempotent, never re-seeded, never duplicated -- the UNIQUE constraint backs this up too).
     */
    private void seedDispositionPolicy(Connection connection, IngestSpec spec) {
        if (spec.suggestedDisposition() == null || spec.suggestedDisposition().isBlank() || spec.sourceType() == null) {
            return;
        }
        Disposition disposition;
        try {
            disposition = Disposition.valueOf(spec.suggestedDisposition());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown suggestedDisposition '{}' declared by connector={} ingest={} -- skipping "
                    + "disposition policy seed", spec.suggestedDisposition(), connection.getConnectorId(), spec.id());
            return;
        }
        String signalType = resolveSignalTypeGlob(spec, connection.getConnectorId());
        if (signalType == null) {
            return;
        }
        String projectId = connection.getProjectId();
        if (dispositionPolicyRepository.findByProjectIdAndSignalTypeAndDisposition(projectId, signalType, disposition).isPresent()) {
            return;
        }
        DispositionPolicy policy = new DispositionPolicy();
        policy.setProjectId(projectId);
        policy.setSignalType(signalType);
        policy.setDisposition(disposition);
        if (spec.suggestedDomain() != null) {
            policy.setConfig(Map.of("domain", spec.suggestedDomain()));
        }
        dispositionPolicyRepository.save(policy);
        dispositionPolicyCache.invalidate(projectId);
        log.info("Seeded disposition policy '{}' -> {} for project {} (connector={} ingest={})",
                signalType, disposition, projectId, connection.getConnectorId(), spec.id());
    }

    /** Resolves {@code sourceType}'s {@code {connector}}/{@code {ingest}} placeholders (same
     *  substitution as {@code SnapshotIngestAdapter#resolveTemplate}), and {@code {period}} (if
     *  present) to {@code SignalGlob}'s single-segment wildcard {@code *} -- a policy row seeded once
     *  at provisioning time must match every future period, not just whichever one happened to be
     *  current when the connection was created. */
    private String resolveSignalTypeGlob(IngestSpec spec, String connectorId) {
        String template = spec.sourceType();
        if (template == null) {
            return null;
        }
        return template
                .replace("{connector}", connectorId)
                .replace("{ingest}", spec.id())
                .replace("{period}", "*");
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
