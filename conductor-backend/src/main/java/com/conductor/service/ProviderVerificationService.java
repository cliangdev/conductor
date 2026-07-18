package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ClaudeApiPreflight;
import com.conductor.exception.BusinessException;
import com.conductor.exception.CredentialEncryptionException;
import com.conductor.workflow.ClaudeCodeRuntimePreflight;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Cross-module orchestration for provider "Connected" → "Verified" (see {@code ClaudeApiPreflight},
 * {@code ClaudeCodeRuntimePreflight}) — lives here rather than in {@code agent.credential} so that
 * package stays connector/workflow-independent (mirrors {@link RuntimeTargetService}'s role composing
 * connector + runtime-target concerns).
 *
 * <p>Deliberately NOT {@code @Transactional} at the class or {@link #verify} level: probes make slow
 * external calls (an Anthropic request, a Cloud Run gRPC call) and must not hold a DB connection across
 * them — same convention as {@link RuntimeTargetService#create}. The persist step is one isolated save
 * via {@link ProviderCredentialService#recordVerification}.
 */
@Service
public class ProviderVerificationService {

    private static final String CLAUDE = "claude";
    private static final String CLAUDE_CODE = "claude-code";

    private final ProviderCredentialService providerCredentialService;
    private final ClaudeApiPreflight claudeApiPreflight;
    private final ClaudeCodeRuntimePreflight claudeCodeRuntimePreflight;
    private final ObjectMapper objectMapper;

    public ProviderVerificationService(ProviderCredentialService providerCredentialService,
                                       ClaudeApiPreflight claudeApiPreflight,
                                       ClaudeCodeRuntimePreflight claudeCodeRuntimePreflight,
                                       ObjectMapper objectMapper) {
        this.providerCredentialService = providerCredentialService;
        this.claudeApiPreflight = claudeApiPreflight;
        this.claudeCodeRuntimePreflight = claudeCodeRuntimePreflight;
        this.objectMapper = objectMapper;
    }

    /** Per-check status — {@code doctor}'s {@code checks[] {name, status, message}} shape. */
    public enum CheckStatus {
        PASS, FAIL, WARN;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A report's overall outcome: {@code error} iff any {@link Check} in it is {@link CheckStatus#FAIL}. */
    public enum ReportStatus {
        VERIFIED, ERROR;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Check(String name, CheckStatus status, String message) {}

    public record VerificationReport(String provider, ReportStatus status, OffsetDateTime checkedAt, List<Check> checks) {}

    /**
     * Runs the appropriate preflight for {@code provider} and persists the outcome onto the credential
     * row, if one exists. {@code claude-code} may be verified with no row at all — this is how the UI
     * probes builtin runtime readiness before a subscription token is ever stored — in which case the
     * report is returned but nothing is persisted (there is no row to persist onto).
     */
    public VerificationReport verify(String projectId, String provider) {
        List<Check> checks = switch (provider) {
            case CLAUDE -> verifyClaude(projectId);
            case CLAUDE_CODE -> claudeCodeRuntimePreflight.check(projectId);
            default -> List.of(new Check(provider, CheckStatus.WARN,
                    "Verification is not supported for provider '" + provider + "'"));
        };

        ReportStatus overall = checks.stream().anyMatch(c -> c.status() == CheckStatus.FAIL)
                ? ReportStatus.ERROR : ReportStatus.VERIFIED;
        VerificationReport report = new VerificationReport(provider, overall, OffsetDateTime.now(), checks);

        providerCredentialService.recordVerification(
                projectId, provider, report.checkedAt(), overall.value(), writeReport(report));
        return report;
    }

    private List<Check> verifyClaude(String projectId) {
        Optional<String> apiKey;
        try {
            apiKey = providerCredentialService.resolveApiKey(projectId, CLAUDE);
        } catch (CredentialEncryptionException e) {
            return List.of(new Check("credential-decrypt", CheckStatus.FAIL,
                    "Could not decrypt the stored API key — re-enter it in Settings → AI Providers"));
        }
        if (apiKey.isEmpty()) {
            return List.of(new Check("credential-decrypt", CheckStatus.FAIL,
                    "No Claude API key is stored for this project yet"));
        }
        return claudeApiPreflight.check(apiKey.get());
    }

    private String writeReport(VerificationReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize verification report: " + e.getMessage());
        }
    }
}
