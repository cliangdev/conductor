package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.exception.BusinessException;
import com.conductor.verification.Check;
import com.conductor.verification.CheckStatus;
import com.conductor.verification.ProviderPreflight;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cross-module orchestration for provider "Connected" → "Verified" (see {@code ClaudeApiPreflight},
 * {@code OpenAiApiPreflight}, {@code ClaudeCodeRuntimePreflight}) — lives here rather than in {@code
 * agent.credential} so that package stays connector/workflow-independent (mirrors {@link
 * RuntimeTargetService}'s role composing connector + runtime-target concerns).
 *
 * <p>Dispatches by {@link ProviderPreflight#provider()} rather than a hardcoded switch — Spring collects
 * every {@link ProviderPreflight} bean (same auto-discovery pattern as {@code ModelProviderRegistry} /
 * {@code ConnectorRegistry}), so a new provider earns a "Verified" badge the moment its preflight bean
 * exists, with no edit here. A provider with no registered preflight gets a warn-only report instead —
 * see {@link #verify}.
 *
 * <p>Deliberately NOT {@code @Transactional} at the class or {@link #verify} level: probes make slow
 * external calls (an Anthropic/OpenAI request, a Cloud Run gRPC call) and must not hold a DB connection
 * across them — same convention as {@link RuntimeTargetService#create}. The persist step is one isolated
 * save via {@link ProviderCredentialService#recordVerification}.
 */
@Service
public class ProviderVerificationService {

    private final ProviderCredentialService providerCredentialService;
    private final List<ProviderPreflight> preflights;
    private final ObjectMapper objectMapper;

    private Map<String, ProviderPreflight> preflightsByProvider;

    public ProviderVerificationService(ProviderCredentialService providerCredentialService,
                                       List<ProviderPreflight> preflights,
                                       ObjectMapper objectMapper) {
        this.providerCredentialService = providerCredentialService;
        this.preflights = preflights;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        Map<String, ProviderPreflight> map = new LinkedHashMap<>();
        for (ProviderPreflight p : preflights) {
            map.put(p.provider(), p);
        }
        this.preflightsByProvider = Collections.unmodifiableMap(map);
    }

    /** A report's overall outcome: {@code error} iff any {@link Check} in it is {@link CheckStatus#FAIL}. */
    public enum ReportStatus {
        VERIFIED, ERROR;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record VerificationReport(String provider, ReportStatus status, OffsetDateTime checkedAt, List<Check> checks) {}

    /**
     * Runs the registered {@link ProviderPreflight} for {@code provider} and persists the outcome onto
     * the credential row, if one exists. {@code claude-code} may be verified with no row at all — this is
     * how the UI probes builtin runtime readiness before a subscription token is ever stored — in which
     * case the report is returned but nothing is persisted (there is no row to persist onto).
     *
     * <p>A provider with no registered preflight gets a warn-only report that is deliberately NOT
     * persisted: writing {@code verified} for it would light up a green badge no probe ever earned the
     * moment a new {@code ChatModelProvider} is registered.
     */
    public VerificationReport verify(String projectId, String provider) {
        ProviderPreflight preflight = preflightsByProvider.get(provider);
        List<Check> checks = preflight != null
                ? preflight.check(projectId)
                : List.of(new Check(provider, CheckStatus.WARN,
                        "Verification is not supported for provider '" + provider + "'"));

        ReportStatus overall = checks.stream().anyMatch(c -> c.status() == CheckStatus.FAIL)
                ? ReportStatus.ERROR : ReportStatus.VERIFIED;
        VerificationReport report = new VerificationReport(provider, overall, OffsetDateTime.now(), checks);

        if (preflight != null) {
            providerCredentialService.recordVerification(
                    projectId, provider, report.checkedAt(), overall.value(), writeReport(report));
        }
        return report;
    }

    private String writeReport(VerificationReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize verification report: " + e.getMessage());
        }
    }
}
