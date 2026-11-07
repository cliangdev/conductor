package com.conductor.marketing.switchback;

import com.conductor.marketing.switchback.SwitchbackTestService.SettleSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The daily {@code marketing-switchback-settle} job: settles every RUNNING switchback test whose last week has ended and
 * whose daily data is no longer provisional (see {@link SwitchbackTestService#settleDue}). Running it again settles
 * nothing twice, so a retry or an overlapping run is harmless. It makes no outbound call and writes only when a test is
 * ready, so a quiet day costs a handful of queries.
 */
@Component
public class SwitchbackSettleJob {

    private static final Logger log = LoggerFactory.getLogger(SwitchbackSettleJob.class);

    private final SwitchbackTestService service;
    private final boolean enabled;

    public SwitchbackSettleJob(SwitchbackTestService service,
                               @Value("${conductor.marketing.switchback.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    /** The registry entry point. */
    public void run() {
        if (!enabled) {
            return;
        }
        SettleSummary summary = service.settleDue();
        log.info("switchback_settle settled={} inconclusive={} waiting={} failed={}", summary.settled(),
                summary.inconclusive(), summary.waiting(), summary.failed());
    }
}
