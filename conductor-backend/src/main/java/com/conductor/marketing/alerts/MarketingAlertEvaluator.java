package com.conductor.marketing.alerts;

import com.conductor.marketing.alerts.MarketingAlert.Kind;
import com.conductor.marketing.budget.BudgetPacingService;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.notification.NotificationMessage;
import com.conductor.repository.ConnectionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells a person, in Discord, when marketing data needs them: a figure two sources disagree on, a channel that
 * has stopped delivering, an Apple Ads stop rule that has tripped, or a target that has turned OFF_TRACK on
 * enough data. After each finished channel-metrics pull it re-reads the whole workspace and compares what holds
 * now with what it has already said ({@code marketing_alert_state}).
 *
 * <ul>
 *   <li>a condition that is new, or comes back after resolving, posts once;</li>
 *   <li>a condition already posted posts again only when its figures have moved meaningfully
 *       ({@link MarketingAlert#sameCondition}), and then as an update;</li>
 *   <li>a condition that no longer holds posts one "Resolved" message and is marked resolved, except an
 *       off-track target with no evidence it recovered, or a stopped snapshot, which is held as it is;</li>
 *   <li>nothing is posted for noise, TOO_EARLY, thin or provisional data (see {@link MarketingAlertConditions}).</li>
 * </ul>
 *
 * <h2>Connections</h2>
 * Prod's pool is five connections, so this holds none of its own. The channel-metrics pull runs with no
 * transaction, which makes the event fire at once ({@code fallbackExecution}) on a thread with no connection; every
 * read below is the read service's own short transaction, every state write is a short {@code REQUIRES_NEW}
 * transaction of its own, and the Discord call is made with none open. Published from inside a transaction instead
 * (a future caller, a test), it runs after that commit: the best that can be done there is to hold the committed
 * transaction's connection while these short ones run (see {@code AfterTransaction} in the creative package).
 *
 * <h2>Failure</h2>
 * Nothing thrown here reaches the pull: an alert is never worth a failed ingest. A source that could not be read
 * is neither raised nor resolved from this time (its alerts stay as they are), so an outage cannot read as
 * "everything cleared". A condition is recorded <em>before</em> it is sent, so a message can be lost but never
 * repeated; with no channel to send to it is still recorded, which is why setting a channel up later does not
 * replay every standing condition.
 *
 * <h2>Every pull is evaluated</h2>
 * A workspace's connections pull one after another, and a daily feed pulls once in 24 hours, so a pull whose
 * evaluation is skipped is a state that stays unseen until that feed's next pull. Every pull therefore evaluates
 * the whole workspace and the stored state always reflects the latest one. Nothing is rate-limited here: the
 * stored state makes Discord quiet (a condition posts once when new, once when it moves meaningfully, once when
 * resolved, whatever the number of evaluations), and the cost of another evaluation is a handful of short reads.
 *
 * <h2>Racing evaluations</h2>
 * Cloud Run runs several instances, so two pulls of one workspace can be evaluated at the same moment, read the same
 * state and both decide to post. Nothing in memory can prevent that, so the state row itself decides: a new key is
 * written with {@code INSERT ... ON CONFLICT DO NOTHING}, and a change or a resolution is an {@code UPDATE} that
 * matches only the state and fingerprint that were read. An evaluation posts only when its statement changed one
 * row; the loser changed nothing, so it neither posts nor retries (the next pull reads what the winner wrote).
 * No lock is held while waiting for anything.
 */
@Component
public class MarketingAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(MarketingAlertEvaluator.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_CHANGED = "CHANGED";
    private static final String STATUS_RESOLVED = "RESOLVED";

    private final MarketingOverviewQueryService overviewService;
    private final VerificationLedgerService ledgerService;
    private final BudgetPacingService pacingService;
    private final MarketingAlertStateRepository states;
    private final NotificationDeliveryService delivery;
    private final ConnectionRepository connections;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public MarketingAlertEvaluator(MarketingOverviewQueryService overviewService,
                                   VerificationLedgerService ledgerService,
                                   BudgetPacingService pacingService,
                                   MarketingAlertStateRepository states,
                                   NotificationDeliveryService delivery,
                                   ConnectionRepository connections,
                                   PlatformTransactionManager transactionManager) {
        this(overviewService, ledgerService, pacingService, states, delivery, connections, transactionManager,
                Clock.systemUTC());
    }

    MarketingAlertEvaluator(MarketingOverviewQueryService overviewService,
                            VerificationLedgerService ledgerService,
                            BudgetPacingService pacingService,
                            MarketingAlertStateRepository states,
                            NotificationDeliveryService delivery,
                            ConnectionRepository connections,
                            PlatformTransactionManager transactionManager,
                            Clock clock) {
        this.overviewService = overviewService;
        this.ledgerService = ledgerService;
        this.pacingService = pacingService;
        this.states = states;
        this.delivery = delivery;
        this.connections = connections;
        this.tx = new TransactionTemplate(transactionManager);
        // A caller that is itself inside a transaction (already committed, in an afterCommit callback) must not
        // have these writes join it: they would be silently dropped with it.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * After the publishing transaction commits, or at once when there is none (the channel-metrics pull has
     * none, so without {@code fallbackExecution} this would never run).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChannelMetricsPulled(ChannelMetricsPulledEvent event) {
        try {
            evaluate(event);
        } catch (RuntimeException e) {
            log.warn("Marketing alert evaluation for project {} failed: {}", event.projectId(), e.getMessage(), e);
        }
    }

    // ---- evaluation ----------------------------------------------------------------------------------

    /**
     * What holds now, which kinds of condition could actually be checked this time, and the keys that could not be
     * judged (left as they are, neither raised nor resolved).
     */
    private record Collected(Map<String, MarketingAlert> alerts, Set<Kind> checked, Set<String> held) {}

    private void evaluate(ChannelMetricsPulledEvent event) {
        String projectId = event.projectId();
        Collected now = collect(event);
        if (now.checked().isEmpty()) {
            return;
        }
        Map<String, MarketingAlertState> stored = new HashMap<>();
        for (MarketingAlertState state : states.findByProjectId(projectId)) {
            stored.put(state.getAlertKey(), state);
        }
        Instant at = clock.instant();
        for (MarketingAlert alert : now.alerts().values()) {
            try {
                raise(projectId, alert, stored.get(alert.key()), at);
            } catch (RuntimeException e) {
                log.warn("Marketing alert {} for project {} could not be handled: {}", alert.key(), projectId,
                        e.getMessage());
            }
        }
        for (MarketingAlertState state : stored.values()) {
            Kind kind = Kind.ofKey(state.getAlertKey());
            if (state.isActive() && kind != null && now.checked().contains(kind)
                    && !now.alerts().containsKey(state.getAlertKey()) && !now.held().contains(state.getAlertKey())) {
                try {
                    resolve(projectId, state, at);
                } catch (RuntimeException e) {
                    log.warn("Marketing alert {} for project {} could not be resolved: {}", state.getAlertKey(),
                            projectId, e.getMessage());
                }
            }
        }
    }

    private Collected collect(ChannelMetricsPulledEvent event) {
        String projectId = event.projectId();
        Map<String, MarketingAlert> alerts = new LinkedHashMap<>();
        Set<Kind> checked = EnumSet.noneOf(Kind.class);
        Set<String> held = new HashSet<>();

        Overview overview = readOverview(projectId);
        if (overview != null) {
            Ledger ledger = hasMismatch(overview) ? readLedger(projectId) : null;
            MarketingAlertConditions.fromOverview(overview, ledger, event.connectionId(),
                            () -> isHealthy(event.connectionId()), held)
                    .forEach(a -> alerts.putIfAbsent(a.key(), a));
            checked.addAll(EnumSet.of(Kind.MISMATCH, Kind.STALE, Kind.OFF_TRACK));
        }
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            MarketingAlertConditions.fromStopRules(pacingService.trippedRules(projectId, today))
                    .forEach(a -> alerts.putIfAbsent(a.key(), a));
            checked.add(Kind.STOP_RULE);
        } catch (RuntimeException e) {
            log.warn("Marketing stop rules for project {} could not be read; their alerts are left as they are: {}",
                    projectId, e.getMessage());
        }
        return new Collected(alerts, checked, held);
    }

    private Overview readOverview(String projectId) {
        try {
            return overviewService.overviewWithEveryMetric(projectId, MarketingAlertConditions.WINDOW);
        } catch (RuntimeException e) {
            log.warn("Marketing overview for project {} could not be read; mismatch, stale and off-track alerts"
                    + " are left as they are: {}", projectId, e.getMessage());
            return null;
        }
    }

    private Ledger readLedger(String projectId) {
        try {
            return ledgerService.ledger(projectId, MarketingAlertConditions.WINDOW);
        } catch (RuntimeException e) {
            log.warn("Verification ledger for project {} could not be read; mismatch alerts will not say which"
                    + " source to trust: {}", projectId, e.getMessage());
            return null;
        }
    }

    private static boolean hasMismatch(Overview overview) {
        return overview.channels() != null && overview.channels().stream()
                .anyMatch(c -> c.mismatches() != null && !c.mismatches().isEmpty());
    }

    /**
     * Whether the connection that just pulled is in good standing: the same test the overview applies to call a
     * connection live (not DEGRADED, not SETUP_REQUIRED). Unknown reads as not healthy, so a stale channel is
     * never excused on a guess.
     */
    private boolean isHealthy(String connectionId) {
        if (connectionId == null) {
            return false;
        }
        try {
            return connections.findById(connectionId)
                    .map(c -> !"DEGRADED".equals(c.getHealthStatus()) && !"SETUP_REQUIRED".equals(c.getHealthStatus()))
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- state changes and posts ---------------------------------------------------------------------

    /**
     * A condition that holds: post it when it is new, back after resolving, or has moved; otherwise stay quiet.
     * The state change comes first and is conditional on the row still being what was read; only the evaluation
     * that made it posts.
     */
    private void raise(String projectId, MarketingAlert alert, MarketingAlertState state, Instant at) {
        OffsetDateTime now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        String details = detailsJson(alert);
        if (state == null) {
            boolean won = inTx(() -> states.insertIfAbsent(UUID.randomUUID().toString(), projectId, alert.key(),
                    alert.fingerprint(), details, now)) == 1;
            if (won) {
                post(projectId, alert.key(), alert.kind(), STATUS_NEW, alert.title(), alert.text(), at);
            }
            return;
        }
        if (state.isActive()) {
            if (alert.sameCondition(state.getFingerprint())) {
                return;
            }
            boolean won = reactivate(projectId, alert, state, details, null);
            if (won) {
                post(projectId, alert.key(), alert.kind(), STATUS_CHANGED, "Updated: " + alert.title(), alert.text(),
                        at);
            }
            return;
        }
        if (reactivate(projectId, alert, state, details, now)) {
            post(projectId, alert.key(), alert.kind(), STATUS_NEW, alert.title(), alert.text(), at);
        }
    }

    /** True when this evaluation moved the row from the state it read; false when another one got there first. */
    private boolean reactivate(String projectId, MarketingAlert alert, MarketingAlertState read, String details,
                               OffsetDateTime firstSeenAt) {
        return inTx(() -> states.reactivateIfUnchanged(projectId, alert.key(), read.getState(), read.getFingerprint(),
                alert.fingerprint(), details, firstSeenAt)) == 1;
    }

    /** An alert that held and no longer does: one "Resolved" message, then marked resolved. */
    private void resolve(String projectId, MarketingAlertState state, Instant at) {
        Map<String, Object> details = state.getDetails() == null ? Map.of() : state.getDetails();
        String title = String.valueOf(details.getOrDefault("title", state.getAlertKey()));
        String text = String.valueOf(details.getOrDefault("text", ""));
        OffsetDateTime now = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        boolean won = inTx(() -> states.resolveIfUnchanged(projectId, state.getAlertKey(), state.getFingerprint(),
                now)) == 1;
        if (!won) {
            return;
        }
        post(projectId, state.getAlertKey(), Kind.ofKey(state.getAlertKey()), STATUS_RESOLVED, "Resolved: " + title,
                "This no longer holds in the latest data." + (text.isBlank() ? "" : " It was: " + text), at);
    }

    /** Title, text, kind and channel of what is being said, kept so a later "Resolved" message can repeat it. */
    private static String detailsJson(MarketingAlert alert) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", alert.kind().name());
        details.put("channel", alert.channel());
        details.put("title", alert.title());
        details.put("text", alert.text());
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Alert details could not be written: " + e.getMessage(), e);
        }
    }

    /** Runs one short statement in a transaction of its own and returns its row count. */
    private int inTx(java.util.function.IntSupplier statement) {
        Integer rows = tx.execute(status -> statement.getAsInt());
        return rows == null ? 0 : rows;
    }

    /**
     * Sends one message and, when it reached a channel, stamps {@code last_posted_at}. A failed send is logged
     * and forgotten: the condition is already recorded, so it is not sent again.
     */
    private void post(String projectId, String alertKey, Kind kind, String status, String title,
                      String text, Instant at) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("alertKey", alertKey);
        metadata.put("alertKind", kind == null ? "" : kind.name());
        metadata.put("alertStatus", status);
        metadata.put("title", AlertText.cap(title, AlertText.MAX_TITLE));
        metadata.put("text", AlertText.cap(text, AlertText.MAX_TEXT));
        boolean sent;
        try {
            sent = delivery.deliverAlert(NotificationMessage.of(EventType.MARKETING_ALERT, projectId, metadata));
        } catch (RuntimeException e) {
            log.warn("Marketing alert {} for project {} could not be sent: {}", alertKey, projectId,
                    e.getMessage());
            return;
        }
        if (sent) {
            try {
                inTx(() -> states.markPosted(projectId, alertKey, OffsetDateTime.ofInstant(at, ZoneOffset.UTC)));
            } catch (RuntimeException e) {
                log.warn("Marketing alert {} for project {} was sent but its post time could not be recorded: {}",
                        alertKey, projectId, e.getMessage());
            }
        }
    }
}
