package com.conductor.repository;

import com.conductor.entity.GitHubWebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface GitHubWebhookEventRepository extends JpaRepository<GitHubWebhookEvent, String> {

    Optional<GitHubWebhookEvent> findByGithubDeliveryId(String githubDeliveryId);

    List<GitHubWebhookEvent> findByProjectIdAndStatus(String projectId, WebhookEventStatus status);

    @Query("SELECT e FROM GitHubWebhookEvent e WHERE e.status = 'FAILED' AND e.attempts < :maxAttempts AND (e.lastAttemptedAt IS NULL OR e.lastAttemptedAt < :cutoff)")
    List<GitHubWebhookEvent> findRetryableEvents(int maxAttempts, OffsetDateTime cutoff);
}
