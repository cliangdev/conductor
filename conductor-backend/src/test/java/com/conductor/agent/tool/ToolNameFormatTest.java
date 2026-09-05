package com.conductor.agent.tool;

import com.conductor.agent.AgentService;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.tool.coordinator.CoordinatorToolProvider;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.domain.KnowledgeDomainService;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.conductor.knowledge.tool.KnowledgeToolProvider;
import com.conductor.memory.AgentMemoryRepository;
import com.conductor.memory.MemoryRetriever;
import com.conductor.memory.tool.MemoryToolProvider;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ProjectDocService;
import com.conductor.service.ProjectSettingsService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins a real OpenAI constraint (function names must match {@code ^[a-zA-Z0-9_-]{1,64}$}) against
 * every built-in {@link AgentTool}'s bare {@code name()} — {@code AgentExecutionService} advertises the
 * bare name, not the namespaced {@code id()}, to the model.
 *
 * <p>Scoped to the three static, project-agnostic providers ({@link CoordinatorToolProvider},
 * {@link KnowledgeToolProvider}, {@link MemoryToolProvider}): their tool sets are fixed Java classes, so
 * this is a compile-time-ish guarantee. {@code ConnectorToolProvider} and {@code HttpToolProvider} tool
 * names are dynamic (connector-defined / user-defined per project) and are intentionally out of scope
 * here — those are validated at creation time instead, not pinned by a static test.
 */
class ToolNameFormatTest {

    private static final Pattern OPENAI_FUNCTION_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    @Test
    void everyCoordinatorToolNameIsOpenAiSafe() {
        WorkItemService workItemService = mock(WorkItemService.class);
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowTriggerService workflowTriggerService = mock(WorkflowTriggerService.class);
        WorkflowRunRepository workflowRunRepository = mock(WorkflowRunRepository.class);
        WorkflowRunQueryService workflowRunQueryService = mock(WorkflowRunQueryService.class);
        AgentService agentService = mock(AgentService.class);
        ProjectDocService projectDocService = mock(ProjectDocService.class);
        AgentExecutionService agentExecutionService = mock(AgentExecutionService.class);

        CoordinatorToolProvider provider = new CoordinatorToolProvider(workItemService, projectRepository,
                workflowService, workflowTriggerService, workflowRunRepository, workflowRunQueryService,
                agentService, projectDocService, agentExecutionService, new ObjectMapper());

        assertToolNamesAreOpenAiSafe(provider.available("p1"));
    }

    @Test
    void everyKnowledgeToolNameIsOpenAiSafe() {
        ProjectSettingsService projectSettingsService = mock(ProjectSettingsService.class);
        when(projectSettingsService.isKnowledgeEnabled("p1")).thenReturn(true);

        KnowledgeToolProvider provider = new KnowledgeToolProvider(projectSettingsService,
                mock(KnowledgePageService.class), mock(KnowledgeIngestionService.class),
                mock(KnowledgeSearchService.class), mock(KnowledgeDomainService.class), new ObjectMapper());

        assertToolNamesAreOpenAiSafe(provider.available("p1"));
    }

    @Test
    void everyMemoryToolNameIsOpenAiSafe() {
        MemoryToolProvider provider = new MemoryToolProvider(mock(MemoryRetriever.class),
                mock(AgentMemoryRepository.class), new ObjectMapper(), true);

        assertToolNamesAreOpenAiSafe(provider.available("p1"));
    }

    private void assertToolNamesAreOpenAiSafe(List<AgentTool> tools) {
        assertThat(tools).isNotEmpty();
        for (AgentTool tool : tools) {
            assertThat(tool.name())
                    .as("tool name %s must satisfy OpenAI's function-name pattern", tool.name())
                    .matches(OPENAI_FUNCTION_NAME);
        }
    }
}
