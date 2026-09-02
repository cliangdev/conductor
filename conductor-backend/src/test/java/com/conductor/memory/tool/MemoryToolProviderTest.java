package com.conductor.memory.tool;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.memory.AgentMemory;
import com.conductor.memory.AgentMemoryRepository;
import com.conductor.memory.MemoryRetriever;
import com.conductor.memory.MemoryStatus;
import com.conductor.memory.MemoryType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryToolProviderTest {

    private static final String PROJECT_ID = "proj-1";

    @Mock
    private MemoryRetriever retriever;
    @Mock
    private AgentMemoryRepository repository;

    private MemoryToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new MemoryToolProvider(retriever, repository, new ObjectMapper(), true);
    }

    private AgentMemory memory(String id, String content) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setProjectId(PROJECT_ID);
        m.setAgentId("agent-1");
        m.setMemoryType(MemoryType.DECISION);
        m.setStatus(MemoryStatus.ACTIVE);
        m.setContent(content);
        m.setImportance(7);
        m.setValidFrom(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        m.setCreatedAt(OffsetDateTime.parse("2026-08-01T00:00:00Z"));
        return m;
    }

    @Test
    void sourceIdIsMemory() {
        assertThat(provider.sourceId()).isEqualTo("memory");
    }

    @Test
    void availableReturnsNothingWhenDisabled() {
        MemoryToolProvider disabled = new MemoryToolProvider(retriever, repository, new ObjectMapper(), false);
        assertThat(disabled.available(PROJECT_ID)).isEmpty();
    }

    @Test
    void resolveReturnsEmptyWhenDisabled() {
        MemoryToolProvider disabled = new MemoryToolProvider(retriever, repository, new ObjectMapper(), false);
        assertThat(disabled.resolve(PROJECT_ID, "memory:search_memory")).isEmpty();
    }

    @Test
    void availableListsExactlyOneTool() {
        List<AgentTool> tools = provider.available(PROJECT_ID);
        assertThat(tools).extracting(AgentTool::id).containsExactly("memory:search_memory");
        assertThat(tools).extracting(AgentTool::name).containsExactly("search_memory");
    }

    @Test
    void claudeCodeToolNameIsEmptyByDefault() {
        assertThat(provider.claudeCodeToolName("memory:search_memory")).isEmpty();
    }

    @Test
    void searchMemoryReturnsScoredRowsAndBumpsAccess() throws Exception {
        AgentMemory m1 = memory("mem-1", "we decided X");
        when(retriever.retrieve(PROJECT_ID, "decision about X", 8)).thenReturn(
                List.of(new MemoryRetriever.ScoredMemory(m1, 0.42, 0.5, 0.4)));

        Optional<AgentTool> tool = provider.resolve(PROJECT_ID, "memory:search_memory");
        ToolResult result = tool.get().invoke(Map.of("q", "decision about X"),
                new ToolInvocationContext(PROJECT_ID, "agent-1", "run-1"));

        assertThat(result.ok()).isTrue();
        JsonNode json = new ObjectMapper().readTree(result.payload());
        JsonNode row = json.get(0);
        assertThat(row.get("id").asText()).isEqualTo("mem-1");
        assertThat(row.get("content").asText()).isEqualTo("we decided X");
        assertThat(row.get("type").asText()).isEqualTo("DECISION");
        assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.get("importance").asInt()).isEqualTo(7);
        assertThat(row.get("agentId").asText()).isEqualTo("agent-1");
        assertThat(row.get("score").asDouble()).isCloseTo(0.42, org.assertj.core.data.Offset.offset(1e-9));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(repository).bumpAccess(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("mem-1");
    }

    @Test
    void searchMemoryDefaultsLimitToEight() {
        when(retriever.retrieve(PROJECT_ID, "q", 8)).thenReturn(List.of());

        Optional<AgentTool> tool = provider.resolve(PROJECT_ID, "memory:search_memory");
        tool.get().invoke(Map.of("q", "q"), new ToolInvocationContext(PROJECT_ID, "agent-1", "run-1"));

        verify(retriever).retrieve(PROJECT_ID, "q", 8);
        verify(repository, never()).bumpAccess(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void searchMemoryClampsLimitToMaxTwenty() {
        when(retriever.retrieve(PROJECT_ID, "q", 20)).thenReturn(List.of());

        Optional<AgentTool> tool = provider.resolve(PROJECT_ID, "memory:search_memory");
        tool.get().invoke(Map.of("q", "q", "limit", 500), new ToolInvocationContext(PROJECT_ID, "agent-1", "run-1"));

        verify(retriever).retrieve(PROJECT_ID, "q", 20);
    }

    @Test
    void searchMemoryToolErrorReturnsToolErrorNotThrownException() {
        when(retriever.retrieve(PROJECT_ID, "q", 8)).thenThrow(new RuntimeException("db unreachable"));

        Optional<AgentTool> tool = provider.resolve(PROJECT_ID, "memory:search_memory");
        ToolResult result = tool.get().invoke(Map.of("q", "q"), new ToolInvocationContext(PROJECT_ID, "agent-1", "run-1"));

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("search_memory failed").contains("db unreachable");
    }
}
