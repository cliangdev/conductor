package com.conductor.marketing.alerts;

import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.marketing.budget.BudgetPacingService;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.verification.VerificationLedgerService;
import com.conductor.notification.NotificationDeliveryService;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.conductor.marketing.alerts.AlertFixtures.backendMismatch;
import static com.conductor.marketing.alerts.AlertFixtures.channel;
import static com.conductor.marketing.alerts.AlertFixtures.emptyLedger;
import static com.conductor.marketing.alerts.AlertFixtures.overview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V192 and the evaluator's writes against real Postgres. Deliberately not {@code @Transactional}: the evaluator
 * writes in short transactions of its own, which only shows up when nothing outer swallows them.
 */
class MarketingAlertStateIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private MarketingAlertStateRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setFirebaseUid("alert-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Alert Test");
        User user = userRepository.save(u);
        Project p = new Project();
        p.setName("Alert Test Project");
        p.setKey("AL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        projectId = projectRepository.save(p).getId();
    }

    /** The statements need a read-write transaction, which the evaluator supplies in production. */
    private int inTx(java.util.function.IntSupplier statement) {
        Integer rows = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> statement.getAsInt());
        return rows == null ? 0 : rows;
    }

    private MarketingAlertState state(String key, String stateName) {
        MarketingAlertState s = new MarketingAlertState();
        s.setProjectId(projectId);
        s.setAlertKey(key);
        s.setState(stateName);
        s.setFingerprint("18.7");
        s.setDetails(Map.of("title", "Apple Ads: Installs", "kind", "MISMATCH"));
        s.setFirstSeenAt(OffsetDateTime.now());
        return s;
    }

    @Test
    void aStateRowRoundTripsItsFingerprintAndDetails() {
        MarketingAlertState saved = repository.save(state("mismatch:ADS:installs:BACKEND", "ACTIVE"));

        List<MarketingAlertState> found = repository.findByProjectId(projectId);

        assertThat(saved.getId()).isNotBlank();
        assertThat(found).hasSize(1);
        MarketingAlertState row = found.get(0);
        assertThat(row.getAlertKey()).isEqualTo("mismatch:ADS:installs:BACKEND");
        assertThat(row.getState()).isEqualTo("ACTIVE");
        assertThat(row.getFingerprint()).isEqualTo("18.7");
        assertThat(row.getDetails()).containsEntry("title", "Apple Ads: Installs").containsEntry("kind", "MISMATCH");
        assertThat(row.getFirstSeenAt()).isNotNull();
        assertThat(row.getLastPostedAt()).isNull();
        assertThat(row.getResolvedAt()).isNull();
    }

    @Test
    void aProjectHasOneRowPerAlertKey() {
        repository.saveAndFlush(state("stale:STORE", "ACTIVE"));

        assertThatThrownBy(() -> repository.saveAndFlush(state("stale:STORE", "RESOLVED")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theStateColumnOnlyAcceptsActiveOrResolved() {
        assertThatThrownBy(() -> repository.saveAndFlush(state("stale:STORE", "SNOOZED")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneOfTwoInsertsOfTheSameKeyChangesARow() {
        OffsetDateTime at = OffsetDateTime.now();
        String details = "{\"title\":\"Apple Ads: Installs\"}";

        int first = inTx(() -> repository.insertIfAbsent(UUID.randomUUID().toString(), projectId, "stale:STORE", "x", details, at));
        int second = inTx(() -> repository.insertIfAbsent(UUID.randomUUID().toString(), projectId, "stale:STORE", "x", details, at));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(repository.findByProjectId(projectId)).hasSize(1);
        assertThat(repository.findByProjectId(projectId).get(0).getDetails()).containsEntry("title",
                "Apple Ads: Installs");
    }

    @Test
    void anUpdateAndAResolveOnlyApplyToTheStateThatWasRead() {
        OffsetDateTime at = OffsetDateTime.now();
        inTx(() -> repository.insertIfAbsent(UUID.randomUUID().toString(), projectId, "mismatch:ADS:installs:BACKEND", "18.7",
                "{\"title\":\"t\"}", at));

        int staleUpdate = inTx(() -> repository.reactivateIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "ACTIVE",
                "99.9", "33.3", "{\"title\":\"t2\"}", null));
        int update = inTx(() -> repository.reactivateIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "ACTIVE", "18.7",
                "33.3", "{\"title\":\"t2\"}", null));
        int staleResolve = inTx(() -> repository.resolveIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "18.7", at));
        int resolve = inTx(() -> repository.resolveIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "33.3", at));
        int resolveAgain = inTx(() -> repository.resolveIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "33.3", at));
        int comeBack = inTx(() -> repository.reactivateIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "RESOLVED",
                "33.3", "18.7", "{\"title\":\"t3\"}", at));
        int comeBackAgain = inTx(() -> repository.reactivateIfUnchanged(projectId, "mismatch:ADS:installs:BACKEND", "RESOLVED",
                "33.3", "18.7", "{\"title\":\"t3\"}", at));

        assertThat(List.of(staleUpdate, update, staleResolve, resolve, resolveAgain, comeBack, comeBackAgain))
                .containsExactly(0, 1, 0, 1, 0, 1, 0);
        MarketingAlertState row = repository.findByProjectId(projectId).get(0);
        assertThat(row.getState()).isEqualTo("ACTIVE");
        assertThat(row.getFingerprint()).isEqualTo("18.7");
        assertThat(row.getResolvedAt()).isNull();
        assertThat(row.getDetails()).containsEntry("title", "t3");
    }

    @Test
    void markPostedStampsOnlyThePostTime() {
        OffsetDateTime at = OffsetDateTime.now();
        inTx(() -> repository.insertIfAbsent(UUID.randomUUID().toString(), projectId, "stale:STORE", "x", "{\"title\":\"t\"}", at));

        int rows = inTx(() -> repository.markPosted(projectId, "stale:STORE", at));

        assertThat(rows).isEqualTo(1);
        MarketingAlertState row = repository.findByProjectId(projectId).get(0);
        assertThat(row.getLastPostedAt()).isNotNull();
        assertThat(row.getState()).isEqualTo("ACTIVE");
    }

    @Test
    void theEvaluatorWritesAndResolvesThroughTransactionsOfItsOwn() {
        MarketingOverviewQueryService overviewService = mock(MarketingOverviewQueryService.class);
        VerificationLedgerService ledgerService = mock(VerificationLedgerService.class);
        BudgetPacingService pacingService = mock(BudgetPacingService.class);
        NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        when(delivery.deliverAlert(any())).thenReturn(true);
        when(ledgerService.ledger(anyString(), anyString())).thenReturn(emptyLedger());
        when(pacingService.trippedRules(anyString(), any(LocalDate.class))).thenReturn(List.of());
        when(overviewService.overviewWithEveryMetric(anyString(), anyString())).thenReturn(overview(channel("ADS", "Apple Ads",
                "MISMATCH", List.of(), List.of(backendMismatch("installs", "Installs", 100, 123, 0.187)),
                "ads-conn")));
        FixedClock clock = new FixedClock(Instant.parse("2026-11-05T09:00:00Z"));
        MarketingAlertEvaluator evaluator = new MarketingAlertEvaluator(overviewService, ledgerService, pacingService,
                repository, delivery, mock(ConnectionRepository.class), transactionManager, clock);

        evaluator.onChannelMetricsPulled(new ChannelMetricsPulledEvent(projectId, "ADS", "ads-conn"));

        MarketingAlertState active = repository.findByProjectId(projectId).get(0);
        assertThat(active.getAlertKey()).isEqualTo("mismatch:ADS:installs:BACKEND");
        assertThat(active.getState()).isEqualTo("ACTIVE");
        assertThat(active.getLastPostedAt()).isNotNull();
        assertThat(active.getDetails()).containsKey("text");

        when(overviewService.overviewWithEveryMetric(anyString(), anyString())).thenReturn(overview(channel("ADS", "Apple Ads",
                "LIVE", List.of(), List.of(), "ads-conn")));
        clock.advance(Duration.ofMinutes(11));
        evaluator.onChannelMetricsPulled(new ChannelMetricsPulledEvent(projectId, "ADS", "ads-conn"));

        MarketingAlertState resolved = repository.findByProjectId(projectId).get(0);
        assertThat(resolved.getState()).isEqualTo("RESOLVED");
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(repository.findByProjectId(projectId)).hasSize(1);
    }

    private static final class FixedClock extends Clock {
        private Instant now;

        FixedClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
