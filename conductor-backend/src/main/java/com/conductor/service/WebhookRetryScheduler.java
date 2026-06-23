package com.conductor.service;

import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** Connector-agnostic retry engine: exponential backoff on FAILED events, DEAD-letter after MAX_ATTEMPTS. */
@Component
public class WebhookRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(WebhookRetryScheduler.class);
    private static final int MAX_ATTEMPTS = 5;

    private final WebhookEventRepository eventRepository;
    private final WebhookDispatchService dispatchService;

    public WebhookRetryScheduler(WebhookEventRepository eventRepository,
                                 WebhookDispatchService dispatchService) {
        this.eventRepository = eventRepository;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 60000)
    public void retryFailedEvents() {
        // Minimum backoff (2^1 = 2 min) bounds the DB candidate set; per-event backoff enforced below.
        OffsetDateTime minimumCutoff = OffsetDateTime.now().minusMinutes(2);
        List<WebhookEvent> candidates = eventRepository.findRetryable(MAX_ATTEMPTS, minimumCutoff);

        for (WebhookEvent event : candidates) {
            if (event.getAttempts() >= MAX_ATTEMPTS) {
                log.warn("Marking webhook event {} as DEAD after {} attempts", event.getId(), event.getAttempts());
                event.setStatus(WebhookEventStatus.DEAD);
                eventRepository.save(event);
                continue;
            }
            if (!isReadyForRetry(event)) {
                continue;
            }
            log.info("Retrying webhook event {} (attempt {})", event.getId(), event.getAttempts() + 1);
            dispatchService.dispatch(event);
        }
    }

    private boolean isReadyForRetry(WebhookEvent event) {
        if (event.getLastAttemptedAt() == null) {
            return true;
        }
        long backoffMinutes = (long) Math.pow(2, event.getAttempts());
        OffsetDateTime requiredCutoff = OffsetDateTime.now().minusMinutes(backoffMinutes);
        return !event.getLastAttemptedAt().isAfter(requiredCutoff);
    }
}
