package com.conductor.controller;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentService;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.User;
import com.conductor.generated.api.AgentsApi;
import com.conductor.generated.model.AgentConfig;
import com.conductor.generated.model.AgentProviderInfo;
import com.conductor.generated.model.AgentResponse;
import com.conductor.generated.model.AvailableAgentTool;
import com.conductor.generated.model.CreateAgentRequest;
import com.conductor.generated.model.ProviderCredentialStatus;
import com.conductor.generated.model.SetProviderCredentialRequest;
import com.conductor.generated.model.UpdateAgentRequest;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External CRUD for user-managed named {@link Agent}s plus per-(project, provider) BYO API-key
 * management. Reads gate on project membership; mutations require ADMIN or CREATOR — mirroring
 * {@code IntegrationController}. Provider credentials are never returned in clear: the credential
 * endpoints only report whether a key is configured.
 */
@RestController
public class AgentController implements AgentsApi {

    private final AgentService agentService;
    private final ProviderCredentialService providerCredentialService;
    private final ProjectSecurityService projectSecurityService;
    private final ObjectMapper objectMapper;

    public AgentController(AgentService agentService,
                           ProviderCredentialService providerCredentialService,
                           ProjectSecurityService projectSecurityService,
                           ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.providerCredentialService = providerCredentialService;
        this.projectSecurityService = projectSecurityService;
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
                toConfigMap(request.getConfig()),
                request.getToolIds(),
                request.getState() != null ? request.getState().getValue() : null);
        Agent created = agentService.create(projectId, input);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @Override
    public ResponseEntity<AgentResponse> updateAgent(String projectId, String agentId, UpdateAgentRequest request) {
        requireAdminOrCreator(projectId);
        AgentService.AgentInput input = new AgentService.AgentInput(
                request.getName(),
                request.getSlug(),
                request.getDescription(),
                request.getProvider(),
                request.getModel(),
                request.getSystemPrompt(),
                toConfigMap(request.getConfig()),
                request.getToolIds(),
                request.getState() != null ? request.getState().getValue() : null);
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
        return ResponseEntity.ok(credentialStatus(projectId, provider));
    }

    @Override
    public ResponseEntity<ProviderCredentialStatus> setProviderCredential(
            String projectId, String provider, SetProviderCredentialRequest request) {
        requireAdminOrCreator(projectId);
        providerCredentialService.setApiKey(projectId, provider, request.getApiKey());
        return ResponseEntity.ok(credentialStatus(projectId, provider));
    }

    @Override
    public ResponseEntity<Void> deleteProviderCredential(String projectId, String provider) {
        requireAdminOrCreator(projectId);
        providerCredentialService.deleteCredential(projectId, provider);
        return ResponseEntity.noContent().build();
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
                .map(p -> new AgentProviderInfo().id(p.id()).defaultModel(p.defaultModel()))
                .toList();
        return ResponseEntity.ok(providers);
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
                .isDefault(DefaultAgentSlugs.isDefault(agent.getSlug()))
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt());
    }

    private ProviderCredentialStatus credentialStatus(String projectId, String provider) {
        return new ProviderCredentialStatus()
                .provider(provider)
                .configured(providerCredentialService.hasCredential(projectId, provider));
    }

    private Map<String, Object> toConfigMap(AgentConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (config.getTemperature() != null) {
            map.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            map.put("maxTokens", config.getMaxTokens());
        }
        if (config.getMaxToolTurns() != null) {
            map.put("maxToolTurns", config.getMaxToolTurns());
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

    private void requireMember(String projectId) {
        if (!projectSecurityService.isProjectMember(projectId, currentUser().getId())) {
            throw new AccessDeniedException("Not a member of this project");
        }
    }

    private void requireAdminOrCreator(String projectId) {
        if (!projectSecurityService.isAdminOrCreator(projectId, currentUser().getId())) {
            throw new AccessDeniedException("Requires ADMIN or CREATOR role");
        }
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
