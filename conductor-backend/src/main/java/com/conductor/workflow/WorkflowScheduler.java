package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowSchedule;
import com.conductor.entity.WorkflowScheduleSkip;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowScheduleRepository;
import com.conductor.repository.WorkflowScheduleSkipRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowScheduleRepository scheduleRepository;
    private final WorkflowScheduleSkipRepository skipRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowTriggerService triggerService;
    private final ObjectMapper objectMapper;

    public WorkflowScheduler(WorkflowScheduleRepository scheduleRepository,
                              WorkflowScheduleSkipRepository skipRepository,
                              WorkflowRunRepository runRepository,
                              WorkflowTriggerService triggerService,
                              ObjectMapper objectMapper) {
        this.scheduleRepository = scheduleRepository;
        this.skipRepository = skipRepository;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void poll() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<WorkflowSchedule> dueSchedules = scheduleRepository.findByEnabledTrueAndNextRunAtBefore(now);

        for (WorkflowSchedule schedule : dueSchedules) {
            try {
                processSchedule(schedule, now);
            } catch (Exception e) {
                log.error("Error processing schedule {}: {}", schedule.getId(), e.getMessage(), e);
            }
        }
    }

    private void processSchedule(WorkflowSchedule schedule, OffsetDateTime now) {
        WorkflowDefinition workflow = schedule.getWorkflow();

        if (!workflow.isEnabled()) {
            log.debug("Workflow {} is disabled, skipping schedule {}", workflow.getId(), schedule.getId());
            return;
        }

        if (hasConcurrencySingle(workflow.getYaml())) {
            List<WorkflowRun> activeRuns = runRepository.findByWorkflowIdAndStatusIn(
                    workflow.getId(), List.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.PENDING));
            if (!activeRuns.isEmpty()) {
                String activeRunId = activeRuns.get(0).getId();
                recordSkip(schedule, activeRunId);
                advanceNextRunAt(schedule, now);
                scheduleRepository.save(schedule);
                return;
            }
        }

        String payloadJson = buildSchedulePayload(schedule, now);
        triggerService.fireTrigger(workflow.getId(), "schedule", payloadJson);

        schedule.setLastRunAt(now);
        advanceNextRunAt(schedule, now);
        scheduleRepository.save(schedule);
        log.info("Fired schedule {} for workflow {}", schedule.getId(), workflow.getId());
    }

    private void recordSkip(WorkflowSchedule schedule, String activeRunId) {
        WorkflowScheduleSkip skip = new WorkflowScheduleSkip();
        skip.setSchedule(schedule);
        skip.setSkippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        skip.setReason("Workflow run already in progress (concurrency: single)");
        skip.setRunId(activeRunId);
        skipRepository.save(skip);
        log.info("Skipped schedule {} due to active run {}", schedule.getId(), activeRunId);
    }

    private void advanceNextRunAt(WorkflowSchedule schedule, OffsetDateTime now) {
        OffsetDateTime nextRun = triggerService.computeNextRun(
                schedule.getCronExpression(), now.atZoneSameInstant(ZoneOffset.UTC));
        schedule.setNextRunAt(nextRun);
    }

    private boolean hasConcurrencySingle(String yaml) {
        if (yaml == null) return false;
        try {
            org.yaml.snakeyaml.Yaml snakeYaml = new org.yaml.snakeyaml.Yaml();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = snakeYaml.load(yaml);
            Object concurrency = parsed.get("concurrency");
            return "single".equals(concurrency);
        } catch (Exception e) {
            log.warn("Failed to parse concurrency from workflow YAML: {}", e.getMessage());
            return false;
        }
    }

    private String buildSchedulePayload(WorkflowSchedule schedule, OffsetDateTime firedAt) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "schedule",
                    "scheduleId", schedule.getId(),
                    "firedAt", firedAt.toString(),
                    "cron", schedule.getCronExpression()
            ));
        } catch (Exception e) {
            return "{}";
        }
    }
}
