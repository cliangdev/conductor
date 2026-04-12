package com.conductor.workflow;

import com.conductor.service.WorkflowSecretsService;
import org.springframework.stereotype.Service;

import java.util.Map;

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
