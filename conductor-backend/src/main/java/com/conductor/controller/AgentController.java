package com.conductor.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentService;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.credential.ProviderCredentialService.ProviderCredentialStatusView;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.entity.RuntimeTarget;
import com.conductor.exception.CredentialEncryptionException;
import com.conductor.entity.User;
import com.conductor.generated.api.AgentsApi;
import com.conductor.generated.model.AgentConfig;
import com.conductor.generated.model.AgentProviderInfo;
import com.conductor.generated.model.AgentResponse;
import com.conductor.generated.model.AvailableAgentTool;
import com.conductor.generated.model.ClaudeRuntimeConfig;
import com.conductor.generated.model.CreateAgentRequest;
import com.conductor.generated.model.ProviderCredentialStatus;
import com.conductor.generated.model.ProviderModelInfo;
import com.conductor.generated.model.ProviderModelsResponse;
import com.conductor.generated.model.ProviderVerificationReport;
import com.conductor.generated.model.ProviderVerificationSummary;
import com.conductor.generated.model.SetClaudeRuntimeRequest;
import com.conductor.generated.model.SetProviderCredentialRequest;
import com.conductor.generated.model.UpdateAgentRequest;
import com.conductor.generated.model.VerificationCheck;
import com.conductor.security.ProjectScopedPrincipal;
import com.conductor.service.ClaudeRuntimeService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.ProviderVerificationService;
import com.conductor.service.RuntimeTargetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External CRUD for user-managed named {@link Agent}s plus per-(project, provider) BYO API-key
 * management. Reads gate on project membership -- accepting either a {@link User} principal or a
 * project-scoped machine principal ({@link ProjectScopedPrincipal}: a project API key or a
 * run-scoped MCP token); mutations require ADMIN or CREATOR and, per {@code KnowledgeController}'s
 * precedent, only a real {@link User} principal can hold a project role, so project API keys are
 * cleanly rejected (403) rather than allowed to bypass role checks -- mirroring
 * {@code IntegrationController}. Provider credentials are never returned in clear: the credential
 * endpoints only report whether a key is configured.
 */
@RestController
public class AgentController implements AgentsApi {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;
    private final ProviderCredentialService providerCredentialService;
    private final ProviderVerificationService providerVerificationService;
    private final ClaudeRuntimeService claudeRuntimeService;
    private final RuntimeTargetService runtimeTargetService;
    private final ProjectSecurityService projectSecurityService;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public AgentController(AgentService agentService,
                           ProviderCredentialService providerCredentialService,
                           ProviderVerificationService providerVerificationService,
                           ClaudeRuntimeService claudeRuntimeService,
                           RuntimeTargetService runtimeTargetService,
                           ProjectSecurityService projectSecurityService,
                           ModelProviderRegistry providerRegistry,
                           ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.providerCredentialService = providerCredentialService;
        this.providerVerificationService = providerVerificationService;
        this.claudeRuntimeService = claudeRuntimeService;
        this.runtimeTargetService = runtimeTargetService;
        this.projectSecurityService = projectSecurityService;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<AgentResponse>> listAgents(String projectId) {
        requireMember(projectId);
        List<AgentResponse> agents = agentService.list(projectId).stream()
                .map(this::toResponse).toList();
        return ResponseEntity.ok(agents);
    }

    @Override
    public ResponseEntity<AgentResponse> getAgent(String projectId, String agentId) {
        requireMember(projectId);
        return ResponseEntity.ok(toResponse(agentService.get(projectId, agentId)));
    }

    @Override
    public ResponseEntity<AgentResponse> createAgent(String projectId, CreateAgentRequest request) {
        requireAdminOrCreator(projectId);
        AgentService.AgentInput input = new AgentService.AgentInput(
                request.getName(),
                request.getSlug(),
                request.getDescription(),
                request.getProvider(),
                request.getModel(),
                request.getSystemPrompt(),
                // No prior config to preserve keys from -- a brand-new agent's configJson is exactly
                // what this request's known AgentConfig fields describe.
                toConfigMap(request.getConfig(), Map.of()),
                request.getToolIds(),
                request.getState() != null ? request.getState().getValue() : null,
                request.getAvatarEmoji(),
                request.getAvatarColor() != null ? request.getAvatarColor().getValue() : null,
                request.getTag());
        Agent created = agentService.create(projectId, input);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @Override
    public ResponseEntity<AgentResponse> updateAgent(String projectId, String agentId, UpdateAgentRequest request) {
        requireAdminOrCreator(projectId);
        // Read the stored config *before* building the input so an edit through this known-field DTO
        // (AgentConfig only models temperature/maxTokens/maxToolTurns/runtime/addressable) preserves any
        // key set outside it -- e.g. CoordinatorProvisioner's seededPromptHash, which decides whether a
        // future shipped prompt update may still refresh an otherwise-untouched CEO agent. Without this,
        // saving the Agents form once and forever after treats the prompt as operator-edited.
        Map<String, Object> existingConfig = readConfigMap(agentService.get(projectId, agentId).getConfigJson());
        AgentService.AgentInput input = new AgentService.AgentInput(
                request.getName(),
                request.getSlug(),
                request.getDescription(),
                request.getProvider(),
                request.getModel(),
                request.getSystemPrompt(),
                toConfigMap(request.getConfig(), existingConfig),
                request.getToolIds(),
                request.getState() != null ? request.getState().getValue() : null,
                request.getAvatarEmoji(),
                request.getAvatarColor() != null ? request.getAvatarColor().getValue() : null,
                request.getTag());
        Agent updated = agentService.update(projectId, agentId, input);
        return ResponseEntity.ok(toResponse(updated));
    }

    @Override
    public ResponseEntity<Void> deleteAgent(String projectId, String agentId) {
        requireAdminOrCreator(projectId);
        agentService.delete(projectId, agentId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ProviderCredentialStatus> getProviderCredentialStatus(String projectId, String provider) {
        requireMember(projectId);
        return ResponseEntity.ok(toStatus(providerCredentialService.getStatus(projectId, provider)));
    }

    @Override
    public ResponseEntity<List<ProviderCredentialStatus>> listProviderCredentialStatuses(String projectId) {
        requireMember(projectId);
        List<ProviderCredentialStatus> statuses = providerCredentialService.listStatuses(projectId).stream()
                .map(this::toStatus)
                .toList();
        return ResponseEntity.ok(statuses);
    }

    @Override
    public ResponseEntity<ProviderCredentialStatus> setProviderCredential(
            String projectId, String provider, SetProviderCredentialRequest request) {
        requireAdminOrCreator(projectId);
        providerCredentialService.setApiKey(projectId, provider, request.getApiKey());
        // A probe failure must never fail the PUT — the key is already stored; verify() itself already
        // turns expected failure modes (decrypt error, unreachable Anthropic, ...) into a report rather
        // than an exception, so this catch is only a safety net against something unexpected.
        try {
            providerVerificationService.verify(projectId, provider);
        } catch (RuntimeException e) {
            log.warn("Post-save verification threw for project {} provider {}: {}", projectId, provider, e.getMessage());
        }
        return ResponseEntity.ok(toStatus(providerCredentialService.getStatus(projectId, provider)));
    }

    @Override
    public ResponseEntity<Void> deleteProviderCredential(String projectId, String provider) {
        requireAdminOrCreator(projectId);
        providerCredentialService.deleteCredential(projectId, provider);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ProviderVerificationReport> verifyProviderCredential(String projectId, String provider) {
        requireAdminOrCreator(projectId);
        return ResponseEntity.ok(toReport(providerVerificationService.verify(projectId, provider)));
    }

    @Override
    public ResponseEntity<ClaudeRuntimeConfig> getClaudeRuntime(String projectId) {
        requireMember(projectId);
        return ResponseEntity.ok(toRuntimeConfig(claudeRuntimeService.getConfig(projectId)));
    }

    @Override
    public ResponseEntity<ClaudeRuntimeConfig> setClaudeRuntime(String projectId, SetClaudeRuntimeRequest request) {
        requireAdminOrCreator(projectId);
        ClaudeRuntimeService.ClaudeRuntimeConfig config =
                claudeRuntimeService.setTarget(projectId, request.getRuntimeTargetId());
        return ResponseEntity.ok(toRuntimeConfig(config));
    }

    @Override
    public ResponseEntity<List<AvailableAgentTool>> listAgentTools(String projectId) {
        requireMember(projectId);
        List<AvailableAgentTool> tools = agentService.listAvailableTools(projectId).stream()
                .map(t -> new AvailableAgentTool()
                        .id(t.id())
                        .name(t.name())
                        .description(t.description())
                        .source(t.source()))
                .toList();
        return ResponseEntity.ok(tools);
    }

    @Override
    public ResponseEntity<List<AgentProviderInfo>> listAgentProviders(String projectId) {
        requireMember(projectId);
        List<AgentProviderInfo> providers = agentService.listProviders().stream()
                .map(p -> new AgentProviderInfo().id(p.id()).defaultModel(p.defaultModel())
                        .defaultModelIsLive(p.defaultModelIsLive()))
                .toList();
        return ResponseEntity.ok(providers);
    }

    @Override
    public ResponseEntity<ProviderModelsResponse> listProviderModels(String projectId, String provider) {
        // ADMIN/CREATOR, not just member: this decrypts the project's KMS-wrapped key and makes an
        // outbound call to the vendor with it (same trigger as verifyProviderCredential below) -- a
        // REVIEWER has no business spending that key on demand just by opening the Agents form, which
        // is itself gated on agent.manage.
        requireAdminOrCreator(projectId);
        // An unknown provider, a provider with no stored credential, or a key that won't decrypt is
        // not an error here -- the Agents-form model picker just falls back to free text, and a
        // broken credential already surfaces loudly under Settings -> AI Providers.
        List<ProviderModelInfo> models = discoverModels(projectId, provider).stream()
                .map(m -> new ProviderModelInfo().id(m.id()).latest(m.latest()))
                .toList();
        return ResponseEntity.ok(new ProviderModelsResponse().models(models));
    }

    private List<com.conductor.agent.provider.ModelInfo> discoverModels(String projectId, String provider) {
        try {
            return providerRegistry.findById(provider)
                    .flatMap(p -> providerCredentialService.resolveApiKey(projectId, provider)
                            .map(p::availableModels))
                    .orElse(List.of());
        } catch (CredentialEncryptionException e) {
            return List.of();
        }
    }

    // ---- mapping ----

    private AgentResponse toResponse(Agent agent) {
        return new AgentResponse()
                .id(agent.getId())
                .projectId(agent.getProjectId())
                .name(agent.getName())
                .slug(agent.getSlug())
                .description(agent.getDescription())
                .provider(agent.getProvider())
                .model(agent.getModel())
                .systemPrompt(agent.getSystemPrompt())
                .config(readConfig(agent.getConfigJson()))
                .toolIds(readToolIds(agent.getToolIds()))
                .state(AgentResponse.StateEnum.fromValue(agent.getState()))
                .avatarEmoji(agent.getAvatarEmoji() != null
                        ? agent.getAvatarEmoji() : AgentAvatarDefaults.defaultEmoji(agent.getSlug()))
                .avatarColor(AgentResponse.AvatarColorEnum.fromValue(agent.getAvatarColor() != null
                        ? agent.getAvatarColor() : AgentAvatarDefaults.defaultColor(agent.getSlug())))
                .tag(agent.getTag())
                .isDefault(DefaultAgentSlugs.isDefault(agent.getSlug()))
                .addressable(agent.isAddressable())
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt());
    }

    private ProviderCredentialStatus toStatus(ProviderCredentialStatusView view) {
        ProviderCredentialStatus status = new ProviderCredentialStatus()
                .provider(view.provider())
                .configured(view.configured());
        if (view.lastVerificationStatus() != null && view.lastVerifiedAt() != null) {
            status.verification(new ProviderVerificationSummary()
                    .status(ProviderVerificationSummary.StatusEnum.fromValue(view.lastVerificationStatus()))
                    .checkedAt(view.lastVerifiedAt())
                    .error(firstFailingCheckMessage(view.lastVerificationReport())));
        }
        return status;
    }

    private ProviderVerificationReport toReport(ProviderVerificationService.VerificationReport report) {
        return new ProviderVerificationReport()
                .provider(report.provider())
                .status(ProviderVerificationReport.StatusEnum.fromValue(report.status().value()))
                .checkedAt(report.checkedAt())
                .checks(report.checks().stream()
                        .map(c -> new VerificationCheck()
                                .name(c.name())
                                .status(VerificationCheck.StatusEnum.fromValue(c.status().value()))
                                .message(c.message()))
                        .toList());
    }

    private ClaudeRuntimeConfig toRuntimeConfig(ClaudeRuntimeService.ClaudeRuntimeConfig config) {
        RuntimeTarget target = config.target();
        return new ClaudeRuntimeConfig()
                .source(ClaudeRuntimeConfig.SourceEnum.fromValue(config.source()))
                .runtimeTargetId(config.runtimeTargetId())
                .runtimeTarget(target != null ? runtimeTargetService.toResponse(target) : null)
                .builtinConfigured(config.builtinConfigured());
    }

    /** Best-effort extraction of the first failing check's message from a persisted report — never throws. */
    private String firstFailingCheckMessage(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return null;
        }
        try {
            JsonNode checks = objectMapper.readTree(reportJson).path("checks");
            for (JsonNode check : checks) {
                if ("fail".equals(check.path("status").asText())) {
                    return check.path("message").asText(null);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Every {@code configJson} key {@link AgentConfig} models -- anything else in a stored config
     *  (e.g. {@code seededPromptHash}, written by {@code CoordinatorProvisioner} outside this DTO) is
     *  opaque to this layer and must survive an edit made through it. */
    private static final Set<String> KNOWN_AGENT_CONFIG_KEYS =
            Set.of("temperature", "maxTokens", "maxToolTurns", "runtime", "addressable");

    /**
     * Builds the {@code configJson} map for a create/update from the request's known-field DTO, layered
     * over {@code base} (the agent's current raw config on update, or an empty map on create). Unknown
     * keys in {@code base} pass through untouched; known keys are fully owned by {@code config} -- a
     * null field there omits the key, same as before this preserved unknown keys at all.
     */
    private Map<String, Object> toConfigMap(AgentConfig config, Map<String, Object> base) {
        if (config == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        base.forEach((key, value) -> {
            if (!KNOWN_AGENT_CONFIG_KEYS.contains(key)) {
                map.put(key, value);
            }
        });
        if (config.getTemperature() != null) {
            map.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            map.put("maxTokens", config.getMaxTokens());
        }
        if (config.getMaxToolTurns() != null) {
            map.put("maxToolTurns", config.getMaxToolTurns());
        }
        if (config.getRuntime() != null) {
            map.put("runtime", config.getRuntime().getValue());
        }
        if (config.getAddressable() != null) {
            map.put("addressable", config.getAddressable());
        }
        return map;
    }

    private AgentConfig readConfig(String json) {
        if (json == null || json.isBlank()) {
            return new AgentConfig();
        }
        try {
            return objectMapper.readValue(json, AgentConfig.class);
        } catch (Exception e) {
            return new AgentConfig();
        }
    }

    /** Raw view of a stored {@code configJson}, unlike {@link #readConfig} which drops anything
     *  {@link AgentConfig} doesn't model -- used by {@link #toConfigMap} to preserve those keys. */
    private Map<String, Object> readConfigMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> readToolIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- access control ----

    /**
     * Member-level gate: accepts either a {@link User} principal who is a project member, or a
     * project-scoped machine principal ({@link ProjectScopedPrincipal} -- a project API key or a
     * run-scoped MCP token) whose {@code projectId} matches the requested project. The rule itself
     * lives in {@link ProjectSecurityService#requireProjectAccess}, shared with every other
     * project-scoped controller.
     */
    private void requireMember(String projectId) {
        projectSecurityService.requireProjectAccess(projectId);
    }

    /**
     * Admin/creator-level gate: only a real {@link User} principal can hold a project role, so
     * project-scoped machine principals (project API keys, run-scoped MCP tokens) are rejected with
     * a clean 403 here rather than bypassing the role check -- mirroring
     * {@code KnowledgeController#requireProjectAdmin}.
     */
    private void requireAdminOrCreator(String projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user) || !projectSecurityService.isAdminOrCreator(projectId, user.getId())) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
    }
}
