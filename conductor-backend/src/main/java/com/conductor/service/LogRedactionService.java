package com.conductor.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Redacts a project's secret values out of free-form log/transcript text (simple value replacement
 * over {@link WorkflowSecretsService}). Lives in {@code com.conductor.service} — not the workflow
 * package — so the agent module can redact transcripts without depending on the workflow engine.
 */
@Service
public class LogRedactionService {

    private final WorkflowSecretsService secretsService;

    public LogRedactionService(WorkflowSecretsService secretsService) {
        this.secretsService = secretsService;
    }

    public String redact(String projectId, String logText) {
        if (logText == null || logText.isEmpty()) return logText;
        Map<String, String> secrets = secretsService.resolveSecrets(projectId);
        String result = logText;
        for (String value : secrets.values()) {
            if (value != null && !value.isEmpty()) {
                result = result.replace(value, "***");
            }
        }
        return result;
    }
}
