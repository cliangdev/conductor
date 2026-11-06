package com.conductor.marketing.switchback;

import com.conductor.marketing.switchback.SwitchbackTestService.SettleSummary;
import com.conductor.scheduling.ScheduledJobDefinition;
import com.conductor.scheduling.ScheduledJobDefinition.Trigger;
import com.conductor.scheduling.TestRegistries;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The daily settle job is a named registry job at 06:30 UTC with the same cadence in the Cloud Scheduler script, and it
 * does nothing when switched off.
 */
class SwitchbackTestScheduleTest {

    private static final String JOB = "marketing-switchback-settle";
    private static final Pattern ROW = Pattern.compile("^\\s*\"" + JOB + "\\|([^|]+)\\|[^\"]+\"\\s*$");

    private static ScheduledJobDefinition job() {
        return TestRegistries.mocked().find(JOB).orElseThrow();
    }

    @Test
    void theJobIsRegisteredAsACronThatFiresEveryDayAtSixThirtyUtc() {
        Trigger trigger = job().inAppTrigger();

        assertThat(trigger).isInstanceOf(Trigger.Cron.class);
        CronExpression cron = CronExpression.parse(((Trigger.Cron) trigger).expression());
        ZonedDateTime wednesdayNoon = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime thursday = cron.next(wednesdayNoon);
        assertThat(thursday).isEqualTo(ZonedDateTime.of(2026, 11, 5, 6, 30, 0, 0, ZoneOffset.UTC));
        assertThat(cron.next(thursday)).isEqualTo(ZonedDateTime.of(2026, 11, 6, 6, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void theLeaseOutlastsAFullRun() {
        assertThat(job().leaseTtl()).isGreaterThanOrEqualTo(Duration.ofHours(1));
    }

    @Test
    void theCloudSchedulerScriptFiresItOnTheSameCadence() throws Exception {
        Path script = Path.of("..", "scripts", "setup-cloud-scheduler.sh");
        if (!Files.exists(script)) {
            script = Path.of("scripts", "setup-cloud-scheduler.sh");
        }
        List<String> crons = Files.readAllLines(script).stream().map(ROW::matcher).filter(Matcher::matches)
                .map(m -> m.group(1)).toList();

        assertThat(crons).containsExactly("30 6 * * *");
    }

    @Test
    void theJobSettlesDueTestsWhenEnabled() {
        SwitchbackTestService service = mock(SwitchbackTestService.class);
        when(service.settleDue()).thenReturn(new SettleSummary(1, 0, 0, 0));

        new SwitchbackSettleJob(service, true).run();

        verify(service).settleDue();
    }

    @Test
    void theJobDoesNothingWhenSwitchedOff() {
        SwitchbackTestService service = mock(SwitchbackTestService.class);

        new SwitchbackSettleJob(service, false).run();

        verify(service, never()).settleDue();
    }
}
