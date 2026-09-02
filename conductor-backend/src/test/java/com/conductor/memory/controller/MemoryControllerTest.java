package com.conductor.memory.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.memory.AgentMemory;
import com.conductor.memory.MemoryConflictException;
import com.conductor.memory.MemoryService;
import com.conductor.memory.MemoryStatus;
import com.conductor.memory.MemoryType;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RunTokenService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MemoryController}. Follows the security-filter setup precedent in
 * {@code KnowledgeControllerTest} -- every endpoint here is a plain membership-gated operation (no
 * ADMIN-only paths), so the auth surface exercised is simpler than knowledge's.
 */
@WebMvcTest(MemoryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MemoryControllerTest {

    private static final String PROJECT_ID = "proj-1";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MemoryService memoryService;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private static AgentMemory memory(String id, String content, MemoryType type, MemoryStatus status,
                                       int importance, OffsetDateTime validTo, String supersededBy) {
        AgentMemory m = new AgentMemory();
        m.setId(id);
        m.setProjectId(PROJECT_ID);
        m.setContent(content);
        m.setMemoryType(type);
        m.setStatus(status);
        m.setImportance(importance);
        m.setValidFrom(OffsetDateTime.now().minusDays(1));
        m.setValidTo(validTo);
        m.setSupersededBy(supersededBy);
        m.setAccessCount(0);
        m.setCreatedAt(OffsetDateTime.now().minusDays(1));
        return m;
    }

    // ---- listMemories ----

    @Test
    void list_happyPath_returnsItemsAndTotalAndPassesFiltersThrough() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        AgentMemory m = memory("mem-1", "Prefers dark mode", MemoryType.PREFERENCE, MemoryStatus.ACTIVE, 7, null, null);
        when(memoryService.list(eq(PROJECT_ID), eq("active"), eq(MemoryType.PREFERENCE), eq("agent-1"), eq("dark"),
                eq(25), eq(5)))
                .thenReturn(new MemoryService.MemoryListResult(List.of(m), 1));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token")
                        .param("q", "dark")
                        .param("status", "active")
                        .param("type", "preference")
                        .param("agentId", "agent-1")
                        .param("limit", "25")
                        .param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("mem-1"))
                .andExpect(jsonPath("$.items[0].content").value("Prefers dark mode"))
                .andExpect(jsonPath("$.items[0].type").value("preference"))
                .andExpect(jsonPath("$.items[0].status").value("active"))
                .andExpect(jsonPath("$.items[0].importance").value(7));
    }

    @Test
    void list_limitAboveMax_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token")
                        .param("limit", "201"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_negativeOffset_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token")
                        .param("offset", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_supersededMemory_statusDerivedFromValidTo() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        // Status stored RAW but validTo is set -- the view must report "superseded", not "raw".
        AgentMemory m = memory("mem-2", "old fact", MemoryType.FACT, MemoryStatus.RAW, 5,
                OffsetDateTime.now(), "mem-3");
        when(memoryService.list(eq(PROJECT_ID), isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(new MemoryService.MemoryListResult(List.of(m), 1));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("superseded"))
                .andExpect(jsonPath("$.items[0].supersededBy").value("mem-3"));
    }

    // ---- getMemoryCounts ----

    @Test
    void counts_happyPath_returnsShape() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        when(memoryService.counts(PROJECT_ID)).thenReturn(new MemoryService.MemoryCounts(12, 3, 9, 4));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveTotal").value(12))
                .andExpect(jsonPath("$.raw").value(3))
                .andExpect(jsonPath("$.consolidated").value(9))
                .andExpect(jsonPath("$.superseded").value(4));
    }

    @Test
    void counts_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    // ---- createMemory ----

    @Test
    void create_happyPath_returns201Active() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        AgentMemory created = memory("mem-9", "Deploys go through GitHub Actions", MemoryType.FACT,
                MemoryStatus.ACTIVE, 5, null, null);
        when(memoryService.createManual(eq(PROJECT_ID), eq("Deploys go through GitHub Actions"),
                eq(MemoryType.FACT), eq(5)))
                .thenReturn(created);

        String body = """
                {"content":"Deploys go through GitHub Actions","type":"fact"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("mem-9"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.agentId").doesNotExist());
    }

    @Test
    void create_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        String body = """
                {"content":"Deploys go through GitHub Actions","type":"fact"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/memories")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ---- getMemory ----

    @Test
    void get_includesHistoryAfterSupersession() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        AgentMemory current = memory("mem-new", "Prefers concise replies", MemoryType.PREFERENCE,
                MemoryStatus.ACTIVE, 6, null, null);
        AgentMemory ancestor = memory("mem-old", "Prefers detailed replies", MemoryType.PREFERENCE,
                MemoryStatus.ACTIVE, 6, OffsetDateTime.now().minusHours(1), "mem-new");
        when(memoryService.get(PROJECT_ID, "mem-new")).thenReturn(current);
        when(memoryService.history(PROJECT_ID, "mem-new")).thenReturn(List.of(ancestor));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories/mem-new")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("mem-new"))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].id").value("mem-old"))
                .andExpect(jsonPath("$.history[0].status").value("superseded"));
    }

    @Test
    void get_unknownId_returns404() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        when(memoryService.get(PROJECT_ID, "missing")).thenThrow(new EntityNotFoundException("Memory not found: missing"));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories/missing")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    // ---- updateMemory ----

    @Test
    void update_closedRow_returns409() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        when(memoryService.update(eq(PROJECT_ID), eq("mem-1"), any(), any(), any()))
                .thenThrow(new MemoryConflictException("Cannot update a closed memory: mem-1"));

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/memories/mem-1")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"updated\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void update_happyPath_returns200() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        AgentMemory updated = memory("mem-1", "updated content", MemoryType.FACT, MemoryStatus.ACTIVE, 8, null, null);
        when(memoryService.update(PROJECT_ID, "mem-1", "updated content", null, 8)).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/projects/" + PROJECT_ID + "/memories/mem-1")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"updated content\",\"importance\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("updated content"))
                .andExpect(jsonPath("$.importance").value(8));
    }

    // ---- deleteMemory ----

    @Test
    void delete_thenGet_returns204ThenNotFound() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/memories/mem-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());

        when(memoryService.get(PROJECT_ID, "mem-1")).thenThrow(new EntityNotFoundException("Memory not found: mem-1"));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/memories/mem-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_nonMember_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(delete("/api/v1/projects/" + PROJECT_ID + "/memories/mem-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }
}
