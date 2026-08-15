package com.conductor.agent;

import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.validation.ReservedTags;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowYamlParser workflowYamlParser;

    public AgentService(AgentRepository repository,
                        ModelProviderRegistry providerRegistry,
                        AgentToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        WorkflowDefinitionRepository workflowRepository,
                        WorkflowYamlParser workflowYamlParser) {
        this.repository = repository;
        this.providerRegistry = providerRegistry;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.workflowRepository = workflowRepository;
        this.workflowYamlParser = workflowYamlParser;
    }

    /**
     * A model provider available to agents: its id, the model applied when none is pinned, and whether
     * that blank-model substitution tracks live discovery ({@code defaultModelIsLive}) or is a fixed
     * constant — see {@link com.conductor.agent.provider.ChatModelProvider#defaultModelIsLive()}.
     */
    public record ProviderOption(String id, String defaultModel, boolean defaultModelIsLive) {}

    /** A tool an agent can be bound to, tagged with its canonical source. */
    public record ToolOption(String id, String name, String description, String source) {}

    /** Providers registered with the gateway, id + default model — for the authoring UI. */
    public List<ProviderOption> listProviders() {
        return providerRegistry.providers().stream()
                .map(p -> new ProviderOption(p.id(), p.defaultModel(), p.defaultModelIsLive()))
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
            String state,
            String avatarEmoji,
            String avatarColor,
            String tag) {
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
        validateConfig(input.config());
        agent.setConfigJson(writeJson(input.config(), "{}"));
        agent.setToolIds(writeJson(input.toolIds(), "[]"));
        agent.setState(validateState(input.state(), "DRAFT"));
        agent.setAvatarEmoji(blankToNull(input.avatarEmoji()));
        agent.setAvatarColor(validateColor(input.avatarColor()));
        agent.setTag(validateTag(input.tag()));
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
            validateConfig(input.config());
            agent.setConfigJson(writeJson(input.config(), "{}"));
        }
        if (input.toolIds() != null) {
            agent.setToolIds(writeJson(input.toolIds(), "[]"));
        }
        if (input.state() != null) {
            agent.setState(validateState(input.state(), agent.getState()));
        }
        if (input.avatarEmoji() != null) {
            agent.setAvatarEmoji(blankToNull(input.avatarEmoji()));
        }
        if (input.avatarColor() != null) {
            agent.setAvatarColor(validateColor(input.avatarColor()));
        }
        if (input.tag() != null) {
            // An explicit blank clears the tag (stored as null).
            agent.setTag(validateTag(input.tag()));
        }
        return repository.save(agent);
    }

    @Transactional
    public void delete(String projectId, String agentId) {
        Agent agent = requireAgent(projectId, agentId);
        // Deletion is irreversible and this API is called by LLM tools as well as the UI, so only an
        // agent its owner has already stood down (Draft) can be removed.
        if (!"DRAFT".equals(agent.getState())) {
            throw new BusinessException("Cannot delete an agent in state " + agent.getState()
                    + ". Set the agent to Draft first, then delete it.");
        }
        List<AgentReferencedByWorkflowsException.Reference> references = referencingWorkflows(projectId, agent);
        if (!references.isEmpty()) {
            throw new AgentReferencedByWorkflowsException(references);
        }
        repository.delete(agent);
    }

    /**
     * Automation workflows reference an agent by slug-or-id from an {@code agent} step's
     * {@code with.agent} (see {@code AgentExecutionService#resolveDefinition}'s slug-then-id lookup)
     * — there's no DB foreign key, so this parses each candidate workflow's YAML rather than a
     * repository query. Lifecycle (statechart) workflows have no steps and are skipped, as are rows
     * whose YAML fails to parse (already-broken and none of this delete's business to report).
     */
    private List<AgentReferencedByWorkflowsException.Reference> referencingWorkflows(String projectId, Agent agent) {
        List<AgentReferencedByWorkflowsException.Reference> references = new ArrayList<>();
        for (WorkflowDefinition def : workflowRepository.findByProjectId(projectId)) {
            if (def.isLifecycle() || def.getYaml() == null) {
                continue;
            }
            boolean referenced;
            try {
                referenced = workflowYamlParser.parse(def.getYaml()).jobs().values().stream()
                        .flatMap(job -> job.steps().stream())
                        .anyMatch(step -> "agent".equals(step.type()) && referencesAgent(step, agent));
            } catch (WorkflowYamlException e) {
                continue;
            }
            if (referenced) {
                references.add(new AgentReferencedByWorkflowsException.Reference(def.getId(), def.getName()));
            }
        }
        return references;
    }

    private boolean referencesAgent(StepSpec step, Agent agent) {
        Object ref = step.with().get("agent");
        if (ref == null) {
            return false;
        }
        String value = ref.toString();
        return value.equals(agent.getSlug()) || value.equals(agent.getId());
    }

    // ---- helpers ----

    /**
     * A per-agent {@code runtime} pin ({@code "api"}/{@code "claude-code"} — mirrors
     * {@code com.conductor.workflow.AgentRuntimeResolver}'s constants, duplicated here rather than
     * imported so the agent package never depends on the workflow package) is the only recognized key
     * this layer restricts; every other {@code configJson} key (maxToolTurns, maxTokens, temperature)
     * is opaque here and validated where it's consumed ({@link com.conductor.agent.run.AgentExecutionService}).
     */
    private static final Set<String> VALID_RUNTIMES = Set.of("api", "claude-code");

    private void validateConfig(Map<String, Object> config) {
        if (config == null) return;
        Object runtime = config.get("runtime");
        if (runtime != null && !VALID_RUNTIMES.contains(runtime.toString())) {
            throw new BusinessException("Invalid agent runtime: " + runtime + " (expected one of " + VALID_RUNTIMES + ")");
        }
    }

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

    /** {@code null} leaves the avatar color unset/unchanged; anything else must be a known token. */
    private String validateColor(String color) {
        if (color == null) {
            return null;
        }
        if (!AgentAvatarDefaults.isValidColor(color)) {
            throw new BusinessException("Invalid avatar color: " + color
                    + " (expected one of " + AgentAvatarDefaults.COLOR_TOKENS + ")");
        }
        return color;
    }

    /** {@code null}/blank leaves the tag unset; anything else must not be a reserved value. */
    private String validateTag(String tag) {
        String normalized = blankToNull(tag);
        if (normalized == null) {
            return null;
        }
        if (ReservedTags.isReserved(normalized)) {
            throw new BusinessException("Tag '" + tag + "' is reserved");
        }
        return normalized.trim();
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
