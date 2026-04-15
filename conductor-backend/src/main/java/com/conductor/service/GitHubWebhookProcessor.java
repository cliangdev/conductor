package com.conductor.service;

import com.conductor.entity.GitHubWebhookEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class GitHubWebhookProcessor {

    @Async
    public void processEventAsync(GitHubWebhookEvent event) {
        // TODO: implemented in T3.3
    }

    public void processEvent(GitHubWebhookEvent event) {
        // TODO: implemented in T3.3
    }
}
