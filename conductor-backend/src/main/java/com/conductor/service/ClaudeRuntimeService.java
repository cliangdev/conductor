package com.conductor.service;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.ProjectSettings;
import com.conductor.entity.RuntimeTarget;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.workflow.RuntimeTargetResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Owns the project-level "which Cloud Run target does {@code runs-on: cloud-run} mean here"
 * designation (see {@link ProjectSettings#getClaudeRuntimeTargetId()}) — the UI-managed counterpart to
 * {@link RuntimeTargetResolver}'s engine-side resolution, and the single seam both the
 * {@code claude-code} preflight and the Settings → AI Providers → Runtime GET go through so they can
 * never disagree about what "effective runtime" means for a project.
 */
@Service
public class ClaudeRuntimeService {

    private static final String GCP_CLOUD_RUN_PROVIDER = "gcp-cloud-run";
    private static final String CLAUDE_CODE = "claude-code";

    private final ProjectSettingsRepository projectSettingsRepository;
    private final RuntimeTargetService runtimeTargetService;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final ProviderCredentialService providerCredentialService;

    public ClaudeRuntimeService(ProjectSettingsRepository projectSettingsRepository,
                                RuntimeTargetService runtimeTargetService,
                                RuntimeTargetResolver runtimeTargetResolver,
                                ProviderCredentialService providerCredentialService) {
        this.projectSettingsRepository = projectSettingsRepository;
        this.runtimeTargetService = runtimeTargetService;
        this.runtimeTargetResolver = runtimeTargetResolver;
        this.providerCredentialService = providerCredentialService;
    }

    /** {@code source} is {@code "project-target"} when a designation is set (and still resolvable) or
     *  {@code "builtin"} otherwise; {@code runtimeTargetId}/{@code target} are null in the builtin case. */
    public record ClaudeRuntimeConfig(String source, String runtimeTargetId, RuntimeTarget target,
                                      boolean builtinConfigured) {}

    /**
     * DB-only snapshot for the Settings → AI Providers → Runtime page — cheap enough to recompute on
     * every page load so env drift (e.g. an operator setting {@code GCP_CLOUDRUN_PROJECT_ID} after the
     * fact) is visible immediately, no caching. Never throws: an unresolvable designation (target
     * deleted out from under a stale id — shouldn't happen given the {@code ON DELETE SET NULL} FK, but
     * defensive) degrades to reporting {@code builtin} rather than failing the page.
     */
    @Transactional(readOnly = true)
    public ClaudeRuntimeConfig getConfig(String projectId) {
        boolean builtinConfigured = runtimeTargetResolver.isBuiltinConfigured();
        String designatedId = designatedTargetId(projectId);
        if (designatedId == null) {
            return new ClaudeRuntimeConfig("builtin", null, null, builtinConfigured);
        }
        try {
            RuntimeTarget target = runtimeTargetService.get(projectId, designatedId);
            return new ClaudeRuntimeConfig("project-target", designatedId, target, builtinConfigured);
        } catch (EntityNotFoundException e) {
            return new ClaudeRuntimeConfig("builtin", null, null, builtinConfigured);
        }
    }

    /**
     * Sets (or clears, with {@code targetId == null}) the project's Claude runtime designation.
     * {@code targetId} must name a {@code gcp-cloud-run} target owned by this project — validated via
     * {@link RuntimeTargetService#get}, which also throws if the target belongs to a different project.
     * Always clears the {@code claude-code} credential's stored verification: the effective runtime just
     * changed, so the last probe result no longer means anything (the frontend re-verifies immediately
     * after a successful designation change).
     */
    @Transactional
    public ClaudeRuntimeConfig setTarget(String projectId, String targetId) {
        String normalized = targetId != null && !targetId.isBlank() ? targetId : null;
        if (normalized != null) {
            RuntimeTarget target = runtimeTargetService.get(projectId, normalized);
            if (!GCP_CLOUD_RUN_PROVIDER.equals(target.getProvider())) {
                throw new BusinessException("Runtime target must be a " + GCP_CLOUD_RUN_PROVIDER + " target");
            }
        }

        ProjectSettings settings = projectSettingsRepository.findByProjectId(projectId)
                .orElseGet(() -> {
                    ProjectSettings s = new ProjectSettings();
                    s.setProjectId(projectId);
                    return s;
                });
        settings.setClaudeRuntimeTargetId(normalized);
        projectSettingsRepository.save(settings);

        providerCredentialService.clearVerification(projectId, CLAUDE_CODE);
        return getConfig(projectId);
    }

    /**
     * The single resolution seam {@code ClaudeCodeRuntimePreflight} and {@link #getConfig} both go
     * through — delegates to {@link RuntimeTargetResolver#resolve} for the {@code "cloud-run"} runs-on
     * value so the probe and the real engine resolution can never drift. May throw
     * {@code RuntimeTargetNotReadyException}.
     */
    public Optional<RuntimeTargetResolver.ResolvedRuntime> resolveEffectiveClaudeRuntime(String projectId) {
        return runtimeTargetResolver.resolve(projectId, "cloud-run");
    }

    private String designatedTargetId(String projectId) {
        return projectSettingsRepository.findByProjectId(projectId)
                .map(ProjectSettings::getClaudeRuntimeTargetId)
                .filter(id -> id != null && !id.isBlank())
                .orElse(null);
    }
}
