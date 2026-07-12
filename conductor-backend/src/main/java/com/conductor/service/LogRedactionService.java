package com.conductor.service;

import org.springframework.stereotype.Service;

import java.util.Collection;
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
        return redactValues(logText, secrets.values());
    }

    /**
     * Reusable core: replaces every occurrence of each non-blank value in {@code sensitiveValues} with
     * {@code ***}. Shared with {@link ActionInvocationService}, which already holds the literal secret
     * values it needs redacted (interpolated into an action step's input) rather than a project id to
     * re-resolve them from.
     */
    public static String redactValues(String text, Collection<String> sensitiveValues) {
        if (text == null || text.isEmpty() || sensitiveValues == null) return text;
        String result = text;
        for (String value : sensitiveValues) {
            if (value != null && !value.isEmpty()) {
                result = result.replace(value, "***");
            }
        }
        return result;
    }
}
