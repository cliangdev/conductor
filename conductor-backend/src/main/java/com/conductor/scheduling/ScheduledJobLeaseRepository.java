package com.conductor.scheduling;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * The {@code scheduled_job_lease} table. Each call is its own short transaction (REQUIRES_NEW) so a job
 * body never runs inside one, and all time comparisons use the database clock.
 */
@Repository
public class ScheduledJobLeaseRepository {

    public enum Claim { CLAIMED, LEASED, RECENT }

    private static final int MAX_ERROR_CHARS = 2000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ScheduledJobLeaseRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Takes the job if its lease has expired and it last started at least {@code minSpacing} ago, creating
     * the row on first use. {@code holder} must be unique per claim; release only matches it.
     */
    public Claim claim(String jobName, String holder, Duration leaseTtl, Duration minSpacing) {
        double ttl = leaseTtl.toMillis() / 1000.0;
        double spacing = minSpacing.toMillis() / 1000.0;
        return tx.execute(status -> {
            int claimed = jdbc.update("""
                    INSERT INTO scheduled_job_lease AS l
                        (job_name, leased_until, leased_by, last_started_at, last_status)
                    VALUES (?, now() + make_interval(secs => ?), ?, now(), 'RUNNING')
                    ON CONFLICT (job_name) DO UPDATE
                        SET leased_until = now() + make_interval(secs => ?),
                            leased_by = EXCLUDED.leased_by,
                            last_started_at = now(),
                            last_finished_at = NULL,
                            last_status = 'RUNNING',
                            last_error = NULL
                        WHERE l.leased_until < now()
                          AND (l.last_started_at IS NULL
                               OR l.last_status = 'FAILED'
                               OR l.last_started_at < now() - make_interval(secs => ?))
                    """, jobName, ttl, holder, ttl, spacing);
            if (claimed == 1) {
                return Claim.CLAIMED;
            }
            Boolean leased = jdbc.queryForObject(
                    "SELECT leased_until >= now() FROM scheduled_job_lease WHERE job_name = ?",
                    Boolean.class, jobName);
            return Boolean.TRUE.equals(leased) ? Claim.LEASED : Claim.RECENT;
        });
    }

    /** Frees the lease if {@code holder} still owns it (a lease that expired and was re-claimed is left alone). */
    public void release(String jobName, String holder, String status, String error) {
        String trimmed = error == null ? null
                : error.length() > MAX_ERROR_CHARS ? error.substring(0, MAX_ERROR_CHARS) : error;
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE scheduled_job_lease
                   SET leased_until = now(), last_finished_at = now(), last_status = ?, last_error = ?
                 WHERE job_name = ? AND leased_by = ?
                """, status, trimmed, jobName, holder));
    }
}
