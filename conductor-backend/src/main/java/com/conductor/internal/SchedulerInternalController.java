package com.conductor.internal;

import com.conductor.generated.internal.api.SchedulerInternalApi;
import com.conductor.generated.internal.model.SchedulerJobRunResponse;
import com.conductor.scheduling.ScheduledJobRunner;
import com.conductor.scheduling.ScheduledJobRunner.RunResult;
import com.conductor.scheduling.SchedulerTokenVerifier;
import com.conductor.scheduling.TickOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Cloud Scheduler's HTTP target for the timed jobs in the registry. {@code /internal/**} is permitAll, so
 * the call authenticates itself with a Google OIDC ID token ({@link SchedulerTokenVerifier}); run tokens are
 * minted per run or per target and cannot identify a standing cron caller. The job runs inside the request
 * because Cloud Run only allocates CPU while one is open. A thrown job is a 500 so Cloud Scheduler retries.
 */
@RestController
public class SchedulerInternalController implements SchedulerInternalApi {

    private static final Logger log = LoggerFactory.getLogger(SchedulerInternalController.class);

    private final SchedulerTokenVerifier verifier;
    private final ScheduledJobRunner runner;

    public SchedulerInternalController(SchedulerTokenVerifier verifier, ScheduledJobRunner runner) {
        this.verifier = verifier;
        this.runner = runner;
    }

    @Override
    public ResponseEntity<SchedulerJobRunResponse> runScheduledJob(String job) {
        Optional<String> caller = verifier.verify(bearerToken());
        if (caller.isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        long start = System.nanoTime();
        RunResult result;
        try {
            result = runner.run(job);
        } catch (RuntimeException | Error e) {
            log.atError()
                    .addKeyValue("job", job)
                    .addKeyValue("caller", caller.get())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .setCause(e)
                    .log("scheduler_trigger_failed");
            return ResponseEntity.status(500).build();
        }
        log.atInfo()
                .addKeyValue("job", job)
                .addKeyValue("outcome", result.outcome())
                .addKeyValue("durationMs", result.durationMs())
                .addKeyValue("caller", caller.get())
                .log("scheduler_trigger");
        if (result.outcome() == TickOutcome.UNKNOWN) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new SchedulerJobRunResponse()
                .job(job)
                .outcome(SchedulerJobRunResponse.OutcomeEnum.valueOf(result.outcome().name()))
                .durationMs(result.durationMs()));
    }

    private static String bearerToken() {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
