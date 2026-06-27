package com.conductor.agent;

import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CRUD for user-managed named {@link Agent}s. Validates {@code provider} against the
 * {@link ModelProviderRegistry}, generates a URL-safe per-project-unique {@code slug} from the name
 * when one isn't supplied, and keeps {@code configJson} / {@code toolIds} as JSON strings on the
 * entity (serialized via the shared {@link ObjectMapper}). {@code model} stays nullable so the
 * provider default applies.
 */
@Service
public class AgentService {

    private final AgentRepository repository;
    private final ModelProviderRegistry providerRegistry;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository repository,
                        ModelProviderRegistry providerRegistry,
                        AgentToolRegistry toolRegistry,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.providerRegistry = providerRegistry;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /** A model provider available to agents: its id and the model applied when none is pinned. */
    public record ProviderOption(String id, String defaultModel) {}

    /** A tool an agent can be bound to, tagged with its canonical source. */
    public record ToolOption(String id, String name, String description, String source) {}

    /** Providers registered with the gateway, id + default model — for the authoring UI. */
    public List<ProviderOption> listProviders() {
        return providerRegistry.providers().stream()
                .map(p -> new ProviderOption(p.id(), p.defaultModel()))
                .toList();
    }

    /** Tools available to this project across all sources, tagged with their canonical source. */
    @Transactional(readOnly = true)
    public List<ToolOption> listAvailableTools(String projectId) {
        return toolRegistry.availableToolsWithSource(projectId).stream()
                .map(st -> new ToolOption(st.tool().id(), st.tool().name(), st.tool().description(), st.source()))
                .toList();
    }

    /**
     * Mutable input for create/update. For update, a {@code null} field means "leave unchanged";
     * {@code config}/{@code toolIds} are replaced wholesale when non-null.
     */
    public record AgentInput(
            String name,
            String slug,
            String description,
            String provider,
            String model,
            String systemPrompt,
            Map<String, Object> config,
            List<String> toolIds,
            String state) {
    }

    @Transactional(readOnly = true)
    public List<Agent> list(String projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Agent get(String projectId, String agentId) {
        return requireAgent(projectId, agentId);
    }

    @Transactional
    public Agent create(String projectId, AgentInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new BusinessException("Agent name is required");
        }
        if (input.provider() == null || input.provider().isBlank()) {
            throw new BusinessException("Agent provider is required");
        }
        validateProvider(input.provider());

        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName(input.name().trim());
        agent.setSlug(resolveSlug(projectId, input.slug(), input.name(), null));
        agent.setDescription(blankToNull(input.description()));
        agent.setProvider(input.provider());
        agent.setModel(blankToNull(input.model()));
        agent.setSystemPrompt(blankToNull(input.systemPrompt()));
        agent.setConfigJson(writeJson(input.config(), "{}"));
        agent.setToolIds(writeJson(input.toolIds(), "[]"));
        agent.setState(validateState(input.state(), "DRAFT"));
        return repository.save(agent);
    }

    @Transactional
    public Agent update(String projectId, String agentId, AgentInput input) {
        Agent agent = requireAgent(projectId, agentId);
        if (input == null) {
            return agent;
        }
        if (input.name() != null) {
            if (input.name().isBlank()) {
                throw new BusinessException("Agent name cannot be blank");
            }
            agent.setName(input.name().trim());
        }
        if (input.slug() != null) {
            agent.setSlug(resolveSlug(projectId, input.slug(), agent.getName(), agent.getId()));
        }
        if (input.description() != null) {
            agent.setDescription(blankToNull(input.description()));
        }
        if (input.provider() != null) {
            validateProvider(input.provider());
            agent.setProvider(input.provider());
        }
        if (input.model() != null) {
            // An explicit blank clears the pin back to the provider default (stored as null).
            agent.setModel(blankToNull(input.model()));
        }
        if (input.systemPrompt() != null) {
            agent.setSystemPrompt(blankToNull(input.systemPrompt()));
        }
        if (input.config() != null) {
            agent.setConfigJson(writeJson(input.config(), "{}"));
        }
        if (input.toolIds() != null) {
            agent.setToolIds(writeJson(input.toolIds(), "[]"));
        }
        if (input.state() != null) {
            agent.setState(validateState(input.state(), agent.getState()));
        }
        return repository.save(agent);
    }

    @Transactional
    public void delete(String projectId, String agentId) {
        Agent agent = requireAgent(projectId, agentId);
        repository.delete(agent);
    }

    // ---- helpers ----

    private Agent requireAgent(String projectId, String agentId) {
        Agent agent = repository.findById(agentId)
                .orElseThrow(() -> new EntityNotFoundException("Agent not found: " + agentId));
        if (!agent.getProjectId().equals(projectId)) {
            throw new EntityNotFoundException("Agent not found in project: " + agentId);
        }
        return agent;
    }

    private void validateProvider(String provider) {
        if (providerRegistry.findById(provider).isEmpty()) {
            throw new BusinessException("Unknown model provider: " + provider
                    + ". Known providers: " + providerRegistry.providerIds());
        }
    }

    private String validateState(String state, String fallback) {
        if (state == null) {
            return fallback;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("DRAFT") && !normalized.equals("ACTIVE")) {
            throw new BusinessException("Invalid agent state: " + state + " (expected DRAFT or ACTIVE)");
        }
        return normalized;
    }

    /**
     * Resolve a per-project-unique slug. Uses the supplied slug if present, otherwise slugifies the
     * name. {@code selfId} (nullable) is the agent being updated, so its own existing slug doesn't
     * collide with itself.
     */
    private String resolveSlug(String projectId, String requested, String name, String selfId) {
        String base = slugify(requested != null && !requested.isBlank() ? requested : name);
        if (base.isEmpty()) {
            throw new BusinessException("Could not derive a slug from the agent name");
        }
        repository.findByProjectIdAndSlug(projectId, base).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("An agent with slug '" + base + "' already exists in this project");
            }
        });
        return base;
    }

    private String slugify(String input) {
        if (input == null) {
            return "";
        }
        String slug = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-+$", "") : slug;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private String writeJson(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid JSON payload: " + e.getOriginalMessage());
        }
    }
}
