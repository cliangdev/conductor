package com.conductor.observability;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledJobMetricsTest {

    static class Job {
        int seenQueries;

        public void tick() {
            RequestStats.recordQuery("select 1", 1, 1);
            seenQueries = RequestStats.get().dbQueries();
        }
    }

    @Test
    void opensAScopeAroundTheJobAndNamesItClassDotMethod() throws Exception {
        Job job = new Job();
        ScheduledMethodRunnable runnable = new ScheduledMethodRunnable(job, Job.class.getMethod("tick"));
        assertThat(ScheduledJobMetrics.jobName(runnable)).isEqualTo("Job.tick");

        ScheduledJobMetrics.decorate(runnable).run();

        assertThat(job.seenQueries).isEqualTo(1);
        assertThat(RequestStats.get()).isNull();
    }
}
