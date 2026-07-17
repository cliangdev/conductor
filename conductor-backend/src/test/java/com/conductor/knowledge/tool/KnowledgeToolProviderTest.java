package com.conductor.knowledge.tool;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.page.KnowledgeConflictException;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.conductor.service.ProjectSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeToolProviderTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private ProjectSettingsService projectSettingsService;
    @Mock
    private KnowledgePageService pageService;
    @Mock
    private KnowledgeIngestionService ingestionService;
    @Mock
    private KnowledgeSearchService searchService;

    private KnowledgeToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new KnowledgeToolProvider(
                projectSettingsService, pageService, ingestionService, searchService, new ObjectMapper());
    }

    @Test
    void sourceIdIsKnowledge() {
        assertThat(provider.sourceId()).isEqualTo("knowledge");
    }

    @Test
    void availableReturnsNothingWhenKnowledgeDisabled() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);
        assertThat(provider.available(PROJECT_ID)).isEmpty();
    }

    @Test
    void resolveReturnsEmptyWhenKnowledgeDisabled() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(false);
        assertThat(provider.resolve(PROJECT_ID, "knowledge:read_knowledge_pages")).isEmpty();
    }

    @Test
    void availableListsExactlyFourTools() {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        List<AgentTool> tools = provider.available(PROJECT_ID);
        assertThat(tools).extracting(AgentTool::id).containsExactlyInAnyOrder(
                "knowledge:read_knowledge_pages", "knowledge:read_knowledge_sources",
                "knowledge:search_knowledge", "knowledge:write_knowledge_pages");
        // Bare names (no source prefix) so one system prompt works on both runtimes.
        assertThat(tools).extracting(AgentTool::name).containsExactlyInAnyOrder(
                "read_knowledge_pages", "read_knowledge_sources", "search_knowledge", "write_knowledge_pages");
    }

    @Test
    void claudeCodeToolNameMapsToMcpConductorPrefixedBareName() {
        assertThat(provider.claudeCodeToolName("knowledge:read_knowledge_pages"))
                .contains("mcp__conductor__read_knowledge_pages");
        assertThat(provider.claudeCodeToolName("knowledge:write_knowledge_pages"))
                .contains("mcp__conductor__write_knowledge_pages");
    }

    @Test
    void claudeCodeToolNameEmptyForUnknownToolId() {
        assertThat(provider.claudeCodeToolName("knowledge:not_a_real_tool")).isEmpty();
        assertThat(provider.claudeCodeToolName("connector:posthog/summary")).isEmpty();
    }

    @Test
    void writeKnowledgePagesConflictResultMatchesMcpToolShapeExactly() throws Exception {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        List<KnowledgeConflictException.Conflict> conflicts = List.of(
                new KnowledgeConflictException.Conflict("dir/page.md", 3, "---\ntype: feature\n---\nold content"));
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), anyList(), any(Actor.class)))
                .thenThrow(new KnowledgeConflictException(conflicts));

        Optional<AgentTool> tool = provider.resolve(PROJECT_ID, "knowledge:write_knowledge_pages");
        assertThat(tool).isPresent();

        Map<String, Object> args = Map.of("writes", List.of(
                Map.of("path", "dir/page.md", "content", "new content", "baseVersion", 2)));
        ToolResult result = tool.get().invoke(args, new ToolInvocationContext(PROJECT_ID, "agent-1", "run-1"));

        assertThat(result.ok()).isTrue();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(result.payload());
        assertThat(json.get("conflict").asBoolean()).isTrue();
        assertThat(json.get("message").asText()).contains("re-read them with read_knowledge_pages");
        JsonNode conflict = json.get("conflicts").get(0);
        assertThat(conflict.get("path").asText()).isEqualTo("dir/page.md");
        assertThat(conflict.get("currentVersion").asInt()).isEqualTo(3);
        assertThat(conflict.get("currentContent").asText()).contains("old content");
    }

    @Test
    void writeKnowledgePagesUsesAgentActorFromInvocationContext() throws Exception {
        when(projectSettingsService.isKnowledgeEnabled(PROJECT_ID)).thenReturn(true);
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), anyList(), any(Actor.class)))
                .thenReturn(List.of());

        Optional<AgentTool> tool = provider.resolve(PROJECT_ID, "knowledge:write_knowledge_pages");
        Map<String, Object> args = Map.of("writes", List.of(), "sourceIds", List.of("src-1"));
        tool.get().invoke(args, new ToolInvocationContext(PROJECT_ID, "librarian-agent-id", "run-1"));

        org.mockito.ArgumentCaptor<Actor> actorCaptor = org.mockito.ArgumentCaptor.forClass(Actor.class);
        org.mockito.Mockito.verify(pageService).batchWrite(eq(PROJECT_ID), anyList(), anyList(), actorCaptor.capture());
        assertThat(actorCaptor.getValue().kind()).isEqualTo("agent");
        assertThat(actorCaptor.getValue().id()).isEqualTo("librarian-agent-id");
    }
}
