package com.conductor.scheduling;

import com.conductor.scheduling.ScheduledJobLeaseRepository.Claim;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledJobLeaseRepositoryIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired ScheduledJobLeaseRepository leases;
    @Autowired JdbcTemplate jdbc;

    private static String name() {
        return "it-job-" + UUID.randomUUID();
    }

    private Map<String, Object> row(String name) {
        return jdbc.queryForMap("SELECT * FROM scheduled_job_lease WHERE job_name = ?", name);
    }

    @Test
    void firstClaimCreatesRowAndSecondIsLeased() {
        String n = name();
        assertThat(leases.claim(n, "h1", Duration.ofMinutes(5), Duration.ZERO)).isEqualTo(Claim.CLAIMED);
        assertThat(row(n)).containsEntry("last_status", "RUNNING").containsEntry("leased_by", "h1");
        assertThat(leases.claim(n, "h2", Duration.ofMinutes(5), Duration.ZERO)).isEqualTo(Claim.LEASED);
        assertThat(row(n)).containsEntry("leased_by", "h1");
    }

    @Test
    void releaseAllowsReclaimOnceSpacingHasPassed() {
        String n = name();
        leases.claim(n, "h1", Duration.ofMinutes(5), Duration.ZERO);
        leases.release(n, "h1", "OK", null);
        assertThat(row(n)).containsEntry("last_status", "OK");
        assertThat(row(n).get("last_finished_at")).isNotNull();

        assertThat(leases.claim(n, "h2", Duration.ofMinutes(5), Duration.ofHours(1))).isEqualTo(Claim.RECENT);
        assertThat(leases.claim(n, "h2", Duration.ofMinutes(5), Duration.ZERO)).isEqualTo(Claim.CLAIMED);
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleHolderCannotRelease() {
        String n = name();
        leases.claim(n, "h1", Duration.ofMillis(50), Duration.ZERO);
        jdbc.update("UPDATE scheduled_job_lease SET leased_until = now() - interval '1 second' WHERE job_name = ?", n);
        assertThat(leases.claim(n, "h2", Duration.ofMinutes(5), Duration.ZERO)).isEqualTo(Claim.CLAIMED);

        leases.release(n, "h1", "OK", null);
        assertThat(row(n)).containsEntry("leased_by", "h2").containsEntry("last_status", "RUNNING");
    }

    @Test
    void failedReleaseRecordsError() {
        String n = name();
        leases.claim(n, "h1", Duration.ofMinutes(5), Duration.ZERO);
        leases.release(n, "h1", "FAILED", "java.lang.IllegalStateException: boom");
        assertThat(row(n)).containsEntry("last_status", "FAILED")
                .containsEntry("last_error", "java.lang.IllegalStateException: boom");
    }

    @Test
    void afterAFailedRunTheJobMayBeReclaimedImmediatelyDespiteSpacing() {
        String n = name();
        leases.claim(n, "h1", Duration.ofMinutes(5), Duration.ofHours(12));
        leases.release(n, "h1", "FAILED", "boom");
        assertThat(leases.claim(n, "h2", Duration.ofMinutes(5), Duration.ofHours(12))).isEqualTo(Claim.CLAIMED);
    }

    @Test
    void afterASuccessfulRunSpacingStillApplies() {
        String n = name();
        leases.claim(n, "h1", Duration.ofMinutes(5), Duration.ofHours(12));
        leases.release(n, "h1", "OK", null);
        assertThat(leases.claim(n, "h2", Duration.ofMinutes(5), Duration.ofHours(12))).isEqualTo(Claim.RECENT);
    }
}
