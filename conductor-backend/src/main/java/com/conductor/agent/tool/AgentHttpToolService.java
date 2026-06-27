package com.conductor.agent.tool;

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
 * CRUD for project-scoped {@link AgentHttpTool} definitions. Mirrors {@code AgentService}: generates
 * a per-project-unique slug from the name when one isn't supplied and keeps {@code headersJson} /
 * {@code inputSchemaJson} as JSON strings (serialized via the shared {@link ObjectMapper}).
 *
 * <p>NOTE: a REST controller + OpenAPI for these definitions is deliberately out of scope for this
 * phase (Phase 3) and is tracked as follow-up — this service is the persistence entry point.
 */
@Service
public class AgentHttpToolService {

    private final AgentHttpToolRepository repository;
    private final ObjectMapper objectMapper;

    public AgentHttpToolService(AgentHttpToolRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Mutable input for create/update. For update, a {@code null} field means "leave unchanged". */
    public record HttpToolInput(
            String name,
            String slug,
            String description,
            String method,
            String urlTemplate,
            Map<String, String> headers,
            String bodyTemplate,
            Map<String, Object> inputSchema) {
    }

    @Transactional(readOnly = true)
    public List<AgentHttpTool> list(String projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public AgentHttpTool get(String projectId, String toolId) {
        return require(projectId, toolId);
    }

    @Transactional
    public AgentHttpTool create(String projectId, HttpToolInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new BusinessException("HTTP tool name is required");
        }
        if (input.urlTemplate() == null || input.urlTemplate().isBlank()) {
            throw new BusinessException("HTTP tool urlTemplate is required");
        }
        AgentHttpTool tool = new AgentHttpTool();
        tool.setProjectId(projectId);
        tool.setName(input.name().trim());
        tool.setSlug(resolveSlug(projectId, input.slug(), input.name(), null));
        tool.setDescription(input.description());
        tool.setMethod(normalizeMethod(input.method()));
        tool.setUrlTemplate(input.urlTemplate());
        tool.setHeadersJson(writeJson(input.headers(), "{}"));
        tool.setBodyTemplate(input.bodyTemplate());
        tool.setInputSchemaJson(writeJson(input.inputSchema(), "{}"));
        return repository.save(tool);
    }

    @Transactional
    public AgentHttpTool update(String projectId, String toolId, HttpToolInput input) {
        AgentHttpTool tool = require(projectId, toolId);
        if (input == null) {
            return tool;
        }
        if (input.name() != null) {
            if (input.name().isBlank()) {
                throw new BusinessException("HTTP tool name cannot be blank");
            }
            tool.setName(input.name().trim());
        }
        if (input.slug() != null) {
            tool.setSlug(resolveSlug(projectId, input.slug(), tool.getName(), tool.getId()));
        }
        if (input.description() != null) {
            tool.setDescription(input.description());
        }
        if (input.method() != null) {
            tool.setMethod(normalizeMethod(input.method()));
        }
        if (input.urlTemplate() != null) {
            if (input.urlTemplate().isBlank()) {
                throw new BusinessException("HTTP tool urlTemplate cannot be blank");
            }
            tool.setUrlTemplate(input.urlTemplate());
        }
        if (input.headers() != null) {
            tool.setHeadersJson(writeJson(input.headers(), "{}"));
        }
        if (input.bodyTemplate() != null) {
            tool.setBodyTemplate(input.bodyTemplate());
        }
        if (input.inputSchema() != null) {
            tool.setInputSchemaJson(writeJson(input.inputSchema(), "{}"));
        }
        return repository.save(tool);
    }

    @Transactional
    public void delete(String projectId, String toolId) {
        repository.delete(require(projectId, toolId));
    }

    // ---- helpers ----

    private AgentHttpTool require(String projectId, String toolId) {
        AgentHttpTool tool = repository.findById(toolId)
                .orElseThrow(() -> new EntityNotFoundException("HTTP tool not found: " + toolId));
        if (!tool.getProjectId().equals(projectId)) {
            throw new EntityNotFoundException("HTTP tool not found in project: " + toolId);
        }
        return tool;
    }

    private String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
    }

    private String resolveSlug(String projectId, String requested, String name, String selfId) {
        String base = slugify(requested != null && !requested.isBlank() ? requested : name);
        if (base.isEmpty()) {
            throw new BusinessException("Could not derive a slug from the HTTP tool name");
        }
        repository.findByProjectIdAndSlug(projectId, base).ifPresent(existing -> {
            if (!existing.getId().equals(selfId)) {
                throw new ConflictException("An HTTP tool with slug '" + base + "' already exists in this project");
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
