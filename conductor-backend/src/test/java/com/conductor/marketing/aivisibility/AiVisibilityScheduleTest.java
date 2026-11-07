package com.conductor.marketing.aivisibility;

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

/** The weekly job is a named registry job on Monday 07:00 UTC, with the same cadence in the Cloud Scheduler script. */
class AiVisibilityScheduleTest {

    private static final String JOB = "marketing-ai-visibility";
    private static final Pattern ROW = Pattern.compile("^\\s*\"" + JOB + "\\|([^|]+)\\|[^\"]+\"\\s*$");

    private static ScheduledJobDefinition job() {
        return TestRegistries.mocked().find(JOB).orElseThrow();
    }

    @Test
    void theJobIsRegisteredAsACronThatFiresEveryMondayAtSevenUtc() {
        Trigger trigger = job().inAppTrigger();

        assertThat(trigger).isInstanceOf(Trigger.Cron.class);
        CronExpression cron = CronExpression.parse(((Trigger.Cron) trigger).expression());
        ZonedDateTime wednesday = ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, ZoneOffset.UTC);
        assertThat(cron.next(wednesday)).isEqualTo(ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, ZoneOffset.UTC));
        assertThat(cron.next(cron.next(wednesday))).isEqualTo(ZonedDateTime.of(2026, 11, 16, 7, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void theLeaseOutlastsAFullRunOfSlowAssistantCalls() {
        // 20 calls of up to a minute each is well under an hour; a lease must not expire mid-run
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

        assertThat(crons).containsExactly("0 7 * * 1");
    }
}
