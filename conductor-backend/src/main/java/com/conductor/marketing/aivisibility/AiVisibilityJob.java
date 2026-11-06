package com.conductor.marketing.aivisibility;

import com.conductor.marketing.aivisibility.AiVisibilityService.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The weekly {@code marketing-ai-visibility} job: runs {@link AiVisibilityService#runChecks} for every project that
 * has at least one active question. The service makes the run idempotent per (question, assistant, ISO week), so a
 * second run in the same week, or a retry after a failure, only asks what is still missing. One failing project
 * never stops the others.
 */
@Component
public class AiVisibilityJob {

    private static final Logger log = LoggerFactory.getLogger(AiVisibilityJob.class);

    /** What one run did, for the log and for tests. */
    record Summary(int projects, int recorded, int skipped, int errors, int failedProjects) {}

    private final AiVisibilityService service;
    private final MarketingAiQuestionRepository questionRepository;
    private final boolean enabled;

    public AiVisibilityJob(AiVisibilityService service, MarketingAiQuestionRepository questionRepository,
                           @Value("${conductor.marketing.ai-visibility.enabled:true}") boolean enabled) {
        this.service = service;
        this.questionRepository = questionRepository;
        this.enabled = enabled;
    }

    /** The registry entry point. */
    public void run() {
        if (!enabled) {
            return;
        }
        Summary summary = checkAll();
        log.info("ai_visibility_checked projects={} recorded={} skipped={} errors={} failedProjects={}",
                summary.projects(), summary.recorded(), summary.skipped(), summary.errors(),
                summary.failedProjects());
    }

    Summary checkAll() {
        var projectIds = questionRepository.findProjectIdsWithActiveQuestions();
        int recorded = 0;
        int skipped = 0;
        int errors = 0;
        int failedProjects = 0;
        for (String projectId : projectIds) {
            try {
                RunResult result = service.runChecks(projectId);
                recorded += result.recorded();
                skipped += result.skipped().size();
                errors += result.errors().size();
            } catch (RuntimeException e) {
                failedProjects++;
                log.warn("ai_visibility_project_failed project={} reason={}", projectId, e.getMessage());
            }
        }
        return new Summary(projectIds.size(), recorded, skipped, errors, failedProjects);
    }
}
