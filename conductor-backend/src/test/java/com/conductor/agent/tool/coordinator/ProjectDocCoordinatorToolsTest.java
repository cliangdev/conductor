package com.conductor.agent.tool.coordinator;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.ProjectDoc;
import com.conductor.service.ProjectDocService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link ProjectDocCoordinatorTools}: pins each tool's exact output JSON
 *  shape, and pins the fix that neither tool leaks a raw {@code conductor-image:} storage-path marker
 *  into a model's context (both now run a doc's content through {@code DocImageMarkers.summarize} first,
 *  same as the REST path). */
class ProjectDocCoordinatorToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInvocationContext CTX = new ToolInvocationContext("p1", "agent-1", "run-1");
    private static final String MARKER = "conductor-image:projects/p1/docs/d1/images/pic.png";

    private ProjectDocService projectDocService;
    private ProjectDocCoordinatorTools tools;

    @BeforeEach
    void setUp() {
        projectDocService = mock(ProjectDocService.class);
        tools = new ProjectDocCoordinatorTools(projectDocService, MAPPER);
    }

    private AgentTool tool(String bareName) {
        return tools.tools().stream()
                .filter(t -> t.name().equals(bareName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + bareName));
    }

    private ProjectDoc doc(String id, String title, String content) {
        ProjectDoc d = new ProjectDoc();
        d.setId(id);
        d.setTitle(title);
        d.setContent(content);
        return d;
    }

    @Test
    void offersExactlyTheTwoProjectDocTools() {
        assertThat(tools.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("search_project_docs", "read_project_doc");
    }

    // ---- search_project_docs ----

    @Test
    void searchProjectDocsHappyPathReturnsPinnedShape() throws Exception {
        when(projectDocService.searchDocs("p1", "hello", 20))
                .thenReturn(List.of(doc("d1", "Doc One", "hello world")));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "hello"), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        Map<String, Object> row = (Map<String, Object>) rows.get(0);
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "title", "snippet");
        assertThat(row.get("title")).isEqualTo("Doc One");
        assertThat((String) row.get("snippet")).contains("hello");
    }

    @Test
    void searchProjectDocsNeverLeaksADocImageMarkerIntoTheSnippet() throws Exception {
        when(projectDocService.searchDocs("p1", "cover", 20))
                .thenReturn(List.of(doc("d1", "Doc One", "cover image: " + MARKER + " done")));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "cover"), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        String snippet = (String) ((Map<?, ?>) rows.get(0)).get("snippet");
        assertThat(snippet).doesNotContain("conductor-image:");
        assertThat(snippet).contains("[image]");
    }

    @Test
    void searchProjectDocsRespectsTheClampedLimit() throws Exception {
        when(projectDocService.searchDocs("p1", "x", 2)).thenReturn(
                List.of(doc("d1", "a", "x"), doc("d2", "b", "x")));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "x", "limit", 2), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        assertThat(rows).hasSize(2);
    }

    @Test
    void searchProjectDocsMissingQReturnsError() {
        ToolResult result = tool("search_project_docs").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void searchProjectDocsServiceExceptionBecomesToolError() {
        when(projectDocService.searchDocs(anyString(), anyString(), anyInt())).thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("search_project_docs").invoke(Map.of("q", "x"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- read_project_doc ----

    @Test
    void readProjectDocHappyPathReturnsPinnedShape() throws Exception {
        when(projectDocService.getDoc("p1", "d1")).thenReturn(doc("d1", "Doc One", "full content"));

        ToolResult result = tool("read_project_doc").invoke(Map.of("id", "d1"), CTX);

        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "title", "content");
        assertThat(row.get("content")).isEqualTo("full content");
    }

    @Test
    void readProjectDocNeverLeaksADocImageMarkerIntoTheContent() throws Exception {
        when(projectDocService.getDoc("p1", "d1")).thenReturn(doc("d1", "Doc One", "before " + MARKER + " after"));

        ToolResult result = tool("read_project_doc").invoke(Map.of("id", "d1"), CTX);

        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat((String) row.get("content")).doesNotContain("conductor-image:");
        assertThat((String) row.get("content")).contains("[image]");
    }

    @Test
    void readProjectDocMissingIdReturnsError() {
        ToolResult result = tool("read_project_doc").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void readProjectDocNotFoundBecomesToolError() {
        when(projectDocService.getDoc("p1", "missing")).thenThrow(new EntityNotFoundException("Document not found"));

        ToolResult result = tool("read_project_doc").invoke(Map.of("id", "missing"), CTX);

        assertThat(result.ok()).isFalse();
    }
}
