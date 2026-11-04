package com.conductor.repository;

import com.conductor.entity.ActionInvocation;
import com.conductor.entity.ActionInvocationStatus;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sweep eligibility and the atomic retry claim against real Postgres. Regression for the double
 * publish where the retry sweep re-ran a row a live inline invoke had just inserted (never attempted).
 */
class ActionInvocationRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private ActionInvocationRepository repository;
    @Autowired private TransactionTemplate tx;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private String projectId;
    private String connectionId;

    @BeforeEach
    void setUp() {
        User creator = new User();
        creator.setFirebaseUid("uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Action Invocation Creator");
        creator = userRepository.save(creator);
        Project project = new Project();
        project.setName("Action Invocation Test");
        project.setKey("AI" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        projectId = projectRepository.save(project).getId();
        Connection c = new Connection();
        c.setProjectId(projectId);
        c.setConnectorId("tiktok");
        c.setAuthType("OAUTH2");
        c.setStatus("ACTIVE");
        c.setConfigJson("{}");
        c.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionId = connectionRepository.saveAndFlush(c).getId();
    }

    private ActionInvocation save(ActionInvocationStatus status, int attempts, OffsetDateTime lastAttemptedAt,
                                  OffsetDateTime createdAt) {
        ActionInvocation a = new ActionInvocation();
        a.setProjectId(projectId);
        a.setConnectionId(connectionId);
        a.setConnectorId("tiktok");
        a.setActionId("publish");
        a.setIdempotencyKey("adhoc:" + UUID.randomUUID());
        a.setStatus(status);
        a.setAttempts(attempts);
        a.setLastAttemptedAt(lastAttemptedAt);
        a.setCreatedAt(createdAt);
        return repository.saveAndFlush(a);
    }

    private boolean sweepSees(ActionInvocation a) {
        return repository.findRetryable(OffsetDateTime.now().minusMinutes(2)).stream()
                .anyMatch(r -> r.getId().equals(a.getId()));
    }

    @Test
    void freshNeverAttemptedPendingRow_isNotSweepable_butOldOnesAre() {
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(sweepSees(save(ActionInvocationStatus.PENDING, 0, null, now))).isFalse();
        assertThat(sweepSees(save(ActionInvocationStatus.PENDING, 0, null, now.minusMinutes(30)))).isTrue();
        assertThat(sweepSees(save(ActionInvocationStatus.FAILED, 1, now.minusMinutes(10), now.minusMinutes(20)))).isTrue();
        assertThat(sweepSees(save(ActionInvocationStatus.FAILED, 1, now.minusSeconds(30), now.minusMinutes(20)))).isFalse();
    }

    @Test
    void claimForRetry_onlyOneCallerWins_andFlipsRowToPending() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ActionInvocation a = save(ActionInvocationStatus.FAILED, 1, now.minusMinutes(10), now.minusMinutes(20));
        OffsetDateTime staleBefore = now.minusMinutes(2);

        int first = tx.execute(s -> repository.claimForRetry(a.getId(), ActionInvocationStatus.FAILED, 1, staleBefore, now));
        int second = tx.execute(s -> repository.claimForRetry(a.getId(), ActionInvocationStatus.FAILED, 1, staleBefore, now));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        ActionInvocation reloaded = repository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ActionInvocationStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(2);
    }

    @Test
    void claimForRetry_losesToAnInlineRunThatJustStampedAnAttempt() {
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ActionInvocation a = save(ActionInvocationStatus.PENDING, 1, now.minusSeconds(5), now.minusMinutes(20));

        int claimed = tx.execute(s -> repository.claimForRetry(a.getId(), ActionInvocationStatus.PENDING, 1,
                now.minusMinutes(2), now));

        assertThat(claimed).isZero();
    }
}
