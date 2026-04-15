package com.conductor.service;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.GitHubWebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class GitHubWebhookRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookRetryScheduler.class);
    private static final int MAX_ATTEMPTS = 5;

    private final GitHubWebhookEventRepository webhookEventRepository;
    private final GitHubWebhookProcessor webhookProcessor;

    public GitHubWebhookRetryScheduler(GitHubWebhookEventRepository webhookEventRepository,
                                        GitHubWebhookProcessor webhookProcessor) {
        this.webhookEventRepository = webhookEventRepository;
        this.webhookProcessor = webhookProcessor;
    }

    @Scheduled(fixedDelay = 60000)
    public void retryFailedEvents() {
        // Use the minimum backoff (2^1 = 2 minutes) as the DB-level cutoff to reduce candidates fetched.
        // Per-event exponential backoff is then enforced in Java below.
        OffsetDateTime minimumCutoff = OffsetDateTime.now().minusMinutes(2);
        List<GitHubWebhookEvent> candidates = webhookEventRepository.findRetryableEvents(MAX_ATTEMPTS, minimumCutoff);

        for (GitHubWebhookEvent event : candidates) {
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                log.warn("Marking webhook event {} as DEAD after {} attempts", event.getId(), event.getAttempts());
                event.setStatus(WebhookEventStatus.DEAD);
                webhookEventRepository.save(event);
                continue;
            }

            if (!isReadyForRetry(event)) {
                continue;
            }

            log.info("Retrying webhook event {} (attempt {})", event.getId(), event.getAttempts() + 1);
            webhookProcessor.processEvent(event);
        }
    }

    private boolean isReadyForRetry(GitHubWebhookEvent event) {
        if (event.getLastAttemptedAt() == null) {
            return true;
        }
        long backoffMinutes = (long) Math.pow(2, event.getAttempts());
        OffsetDateTime requiredCutoff = OffsetDateTime.now().minusMinutes(backoffMinutes);
        return !event.getLastAttemptedAt().isAfter(requiredCutoff);
    }
}
