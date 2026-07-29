package com.conductor.agent;

import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link AgentService} — no Spring context, no DB. Focused on the PATCH
 * clear-vs-unchanged semantics and the read-only tool/provider listings.
 */
class AgentServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String AGENT_ID = "agent-1";

    private AgentRepository repository;
    private ModelProviderRegistry providerRegistry;
    private AgentToolRegistry toolRegistry;
    private WorkflowDefinitionRepository workflowRepository;
    private AgentService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentRepository.class);
        providerRegistry = mock(ModelProviderRegistry.class);
        toolRegistry = mock(AgentToolRegistry.class);
        workflowRepository = mock(WorkflowDefinitionRepository.class);
        service = new AgentService(repository, providerRegistry, toolRegistry, new ObjectMapper(),
                workflowRepository, new WorkflowYamlParser());
        when(repository.save(any(Agent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
    }

    private Agent existingAgent() {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setProjectId(PROJECT_ID);
        agent.setName("Marketer");
        agent.setSlug("marketer");
        agent.setProvider("claude");
        agent.setModel("claude-opus-4-8");
        agent.setDescription("an existing description");
        agent.setSystemPrompt("be helpful");
        agent.setConfigJson("{\"temperature\":0.5}");
        agent.setToolIds("[\"connector:posthog/web_analytics_summary\"]");
        agent.setState("ACTIVE");
        when(repository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        return agent;
    }

    private AgentService.AgentInput onlyToolIds(List<String> toolIds) {
        return new AgentService.AgentInput(null, null, null, null, null, null, null, toolIds, null, null, null, null);
    }

    @Test
    void update_explicitBlankModel_clearsPinBackToNull() {
        existingAgent();
        AgentService.AgentInput input =
                new AgentService.AgentInput(null, null, null, null, "  ", null, null, null, null, null, null, null);

        Agent updated = service.update(PROJECT_ID, AGENT_ID, input);

        assertThat(updated.getModel()).isNull();
    }

    @Test
    void update_emptyToolIds_clearsBindings() {
        existingAgent();

        Agent updated = service.update(PROJECT_ID, AGENT_ID, onlyToolIds(List.of()));

        assertThat(updated.getToolIds()).isEqualTo("[]");
    }

    @Test
    void update_nullToolIds_leavesBindingsUnchanged() {
        existingAgent();

        Agent updated = service.update(PROJECT_ID, AGENT_ID, onlyToolIds(null));

        assertThat(updated.getToolIds()).isEqualTo("[\"connector:posthog/web_analytics_summary\"]");
    }

    @Test
    void update_blankDescription_clearsToNull() {
        existingAgent();
        AgentService.AgentInput input =
                new AgentService.AgentInput(null, null, "", null, null, null, null, null, null, null, null, null);

        Agent updated = service.update(PROJECT_ID, AGENT_ID, input);

        assertThat(updated.getDescription()).isNull();
    }

    // ---- avatar ----

    private AgentService.AgentInput createInput(String avatarEmoji, String avatarColor) {
        return new AgentService.AgentInput(
                "Test Agent", null, null, "claude", null, null, null, null, null, avatarEmoji, avatarColor, null);
    }

    @Test
    void create_withoutAvatar_leavesBothFieldsNull() {
        when(providerRegistry.findById("claude")).thenReturn(Optional.of(mock(ChatModelProvider.class)));

        Agent created = service.create(PROJECT_ID, createInput(null, null));

        assertThat(created.getAvatarEmoji()).isNull();
        assertThat(created.getAvatarColor()).isNull();
    }

    @Test
    void create_withExplicitAvatar_persistsBothFields() {
        when(providerRegistry.findById("claude")).thenReturn(Optional.of(mock(ChatModelProvider.class)));

        Agent created = service.create(PROJECT_ID, createInput("🦉", "teal"));

        assertThat(created.getAvatarEmoji()).isEqualTo("🦉");
        assertThat(created.getAvatarColor()).isEqualTo("teal");
    }

    @Test
    void create_withInvalidAvatarColor_throwsBusinessException() {
        when(providerRegistry.findById("claude")).thenReturn(Optional.of(mock(ChatModelProvider.class)));

        assertThatThrownBy(() -> service.create(PROJECT_ID, createInput("🦉", "not-a-color")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid avatar color")
                .hasMessageContaining("not-a-color");
    }

    @Test
    void update_setsAvatarEmojiAndColor() {
        existingAgent();
        AgentService.AgentInput input =
                new AgentService.AgentInput(null, null, null, null, null, null, null, null, null, "🚀", "rose", null);

        Agent updated = service.update(PROJECT_ID, AGENT_ID, input);

        assertThat(updated.getAvatarEmoji()).isEqualTo("🚀");
        assertThat(updated.getAvatarColor()).isEqualTo("rose");
    }

    @Test
    void update_nullAvatarFields_leaveExistingAvatarUnchanged() {
        Agent agent = existingAgent();
        agent.setAvatarEmoji("🧠");
        agent.setAvatarColor("amber");
        AgentService.AgentInput input =
                new AgentService.AgentInput(null, null, null, null, null, null, null, null, null, null, null, null);

        Agent updated = service.update(PROJECT_ID, AGENT_ID, input);

        assertThat(updated.getAvatarEmoji()).isEqualTo("🧠");
        assertThat(updated.getAvatarColor()).isEqualTo("amber");
    }

    @Test
    void update_invalidAvatarColor_throwsBusinessException() {
        existingAgent();
        AgentService.AgentInput input =
                new AgentService.AgentInput(null, null, null, null, null, null, null, null, null, null, "chartreuse", null);

        assertThatThrownBy(() -> service.update(PROJECT_ID, AGENT_ID, input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid avatar color");
    }

    // ---- tag ----

    private AgentService.AgentInput onlyTag(String tag) {
        return new AgentService.AgentInput(null, null, null, null, null, null, null, null, null, null, null, tag);
    }

    @Test
    void create_withReservedTag_throwsBusinessException() {
        when(providerRegistry.findById("claude")).thenReturn(Optional.of(mock(ChatModelProvider.class)));
        AgentService.AgentInput input = new AgentService.AgentInput(
                "Test Agent", null, null, "claude", null, null, null, null, null, null, null, "  Default ");

        assertThatThrownBy(() -> service.create(PROJECT_ID, input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("is reserved");
    }

    @Test
    void update_setsTag() {
        existingAgent();

        Agent updated = service.update(PROJECT_ID, AGENT_ID, onlyTag(" growth "));

        assertThat(updated.getTag()).isEqualTo("growth");
    }

    @Test
    void update_nullTag_leavesTagUnchanged() {
        Agent agent = existingAgent();
        agent.setTag("growth");

        Agent updated = service.update(PROJECT_ID, AGENT_ID, onlyTag(null));

        assertThat(updated.getTag()).isEqualTo("growth");
    }

    @Test
    void update_blankTag_clearsToNull() {
        Agent agent = existingAgent();
        agent.setTag("growth");

        Agent updated = service.update(PROJECT_ID, AGENT_ID, onlyTag(""));

        assertThat(updated.getTag()).isNull();
    }

    // ---- delete guard ----

    @Test
    void delete_activeAgent_isRejectedWithAnActionableMessage() {
        existingAgent();

        assertThatThrownBy(() -> service.delete(PROJECT_ID, AGENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("Set the agent to Draft first");
        Mockito.verify(repository, Mockito.never()).delete(any(Agent.class));
    }

    @Test
    void delete_draftAgent_removesIt() {
        Agent agent = existingAgent();
        agent.setState("DRAFT");

        service.delete(PROJECT_ID, AGENT_ID);

        Mockito.verify(repository).delete(agent);
    }

    private static WorkflowDefinition workflowWithYaml(String id, String name, String yaml) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(id);
        def.setName(name);
        def.setYaml(yaml);
        return def;
    }

    @Test
    void delete_draftAgentReferencedBySlugInAgentStep_isRejectedNamingTheWorkflow() {
        Agent agent = existingAgent();
        agent.setState("DRAFT");
        String yaml = """
                on:
                  webhook: {}
                jobs:
                  review:
                    steps:
                      - type: agent
                        with:
                          agent: marketer
                          task: hi
                """;
        when(workflowRepository.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(workflowWithYaml("wf-1", "PR Review", yaml)));

        assertThatThrownBy(() -> service.delete(PROJECT_ID, AGENT_ID))
                .isInstanceOf(AgentReferencedByWorkflowsException.class)
                .hasMessageContaining("PR Review");
        Mockito.verify(repository, Mockito.never()).delete(any(Agent.class));
    }

    @Test
    void delete_draftAgentReferencedByIdInAgentStep_isRejected() {
        Agent agent = existingAgent();
        agent.setState("DRAFT");
        String yaml = """
                on:
                  webhook: {}
                jobs:
                  review:
                    steps:
                      - type: agent
                        with:
                          agent: agent-1
                          task: hi
                """;
        when(workflowRepository.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(workflowWithYaml("wf-1", "PR Review", yaml)));

        assertThatThrownBy(() -> service.delete(PROJECT_ID, AGENT_ID))
                .isInstanceOf(AgentReferencedByWorkflowsException.class);
    }

    @Test
    void delete_draftAgentUnreferenced_removesItDespiteOtherWorkflows() {
        Agent agent = existingAgent();
        agent.setState("DRAFT");
        String yaml = """
                on:
                  webhook: {}
                jobs:
                  review:
                    steps:
                      - type: agent
                        with:
                          agent: someone-else
                          task: hi
                """;
        when(workflowRepository.findByProjectId(PROJECT_ID))
                .thenReturn(List.of(workflowWithYaml("wf-1", "Other Workflow", yaml)));

        service.delete(PROJECT_ID, AGENT_ID);

        Mockito.verify(repository).delete(agent);
    }

    @Test
    void listProviders_mapsIdAndDefaultModel() {
        ChatModelProvider claude = mock(ChatModelProvider.class);
        when(claude.id()).thenReturn("claude");
        when(claude.defaultModel()).thenReturn("claude-opus-4-8");
        when(providerRegistry.providers()).thenReturn(List.of(claude));

        List<AgentService.ProviderOption> providers = service.listProviders();

        assertThat(providers).singleElement()
                .satisfies(p -> {
                    assertThat(p.id()).isEqualTo("claude");
                    assertThat(p.defaultModel()).isEqualTo("claude-opus-4-8");
                });
    }

    @Test
    void listAvailableTools_carriesCanonicalSource() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.id()).thenReturn("connector:posthog/web_analytics_summary");
        when(tool.name()).thenReturn("posthog_web_analytics_summary");
        when(tool.description()).thenReturn("Summarize web analytics");
        when(toolRegistry.availableToolsWithSource(PROJECT_ID))
                .thenReturn(List.of(new AgentToolRegistry.SourcedTool(tool, "connector")));

        List<AgentService.ToolOption> tools = service.listAvailableTools(PROJECT_ID);

        assertThat(tools).singleElement()
                .satisfies(t -> {
                    assertThat(t.id()).isEqualTo("connector:posthog/web_analytics_summary");
                    assertThat(t.source()).isEqualTo("connector");
                });
        Mockito.verifyNoInteractions(repository);
    }
}
