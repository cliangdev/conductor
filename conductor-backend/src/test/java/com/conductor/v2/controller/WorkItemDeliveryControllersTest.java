package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemAgentSession;
import com.conductor.entity.WorkItemCriterion;
import com.conductor.entity.WorkItemDependency;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemAgentSessionService;
import com.conductor.service.WorkItemCriteriaService;
import com.conductor.service.WorkItemDependencyService;
import com.conductor.workflow.RunTokenService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for the dependency, criteria and agent-session controllers: request binding, role
 * checks, response shape and the 409 a dependency conflict maps to. The rules are in the service tests.
 */
@WebMvcTest({WorkItemDependenciesController.class, WorkItemCriteriaController.class,
        WorkItemAgentSessionsController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class WorkItemDeliveryControllersTest {

    private static final String BASE = "/api/v2/projects/proj-1/work-items/wi-1";
    private static final String AUTH = "Bearer valid-token";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WorkItemDependencyService dependencyService;
    @MockitoBean private WorkItemCriteriaService criteriaService;
    @MockitoBean private WorkItemAgentSessionService sessionService;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    private final Project project = new Project();

    @BeforeEach
    void setUp() {
        project.setId("proj-1");
        project.setKey("COND");
        User user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        asRole(MemberRole.CREATOR);
    }

    private void asRole(MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1")).thenReturn(Optional.of(member));
    }

    private WorkItem item(String id, int sequence, String title) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setProject(project);
        w.setSequenceNumber(sequence);
        w.setTitle(title);
        w.setCurrentStatus("TODO");
        return w;
    }

    // --- dependencies ---

    @Test
    void addDependencyBindsTheBodyAndReturnsTheGroupedListingWithDependencyIds() throws Exception {
        WorkItemDependencyService.Dependencies listing = new WorkItemDependencyService.Dependencies(
                List.of(new WorkItemDependencyService.Entry("dep-1", item("wi-2", 2, "Later"))),
                List.of(), List.of(), List.of());
        when(dependencyService.listFor("proj-1", "wi-1")).thenReturn(listing);

        mockMvc.perform(post(BASE + "/dependencies").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BLOCKS\",\"relatedWorkItemId\":\"wi-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].dependencyId").value("dep-1"))
                .andExpect(jsonPath("$.blocks[0].displayId").value("COND-2"))
                .andExpect(jsonPath("$.blockedBy").isEmpty());

        verify(dependencyService).add(eq("proj-1"), eq("wi-1"), eq("BLOCKS"), eq("wi-2"), any(ProjectActor.class));
    }

    @Test
    void aDependencyCycleMapsTo409() throws Exception {
        when(dependencyService.add(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new ConflictException("COND-1 cannot block COND-2: it would create a cycle"));

        mockMvc.perform(post(BASE + "/dependencies").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BLOCKS\",\"relatedWorkItemId\":\"wi-2\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownDependencyKindIsRejectedAtBinding() throws Exception {
        mockMvc.perform(post(BASE + "/dependencies").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"RELATES\",\"relatedWorkItemId\":\"wi-2\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void aReviewerCannotAddADependencyButCanRead() throws Exception {
        asRole(MemberRole.REVIEWER);
        when(dependencyService.listFor("proj-1", "wi-1")).thenReturn(new WorkItemDependencyService.Dependencies(
                List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(post(BASE + "/dependencies").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BLOCKS\",\"relatedWorkItemId\":\"wi-2\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/dependencies").header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    @Test
    void removeDependencyReturns204() throws Exception {
        mockMvc.perform(delete(BASE + "/dependencies/dep-1").header("Authorization", AUTH))
                .andExpect(status().isNoContent());
        verify(dependencyService).remove("proj-1", "wi-1", "dep-1");
    }

    @Test
    void theGraphMapsNodesEdgesAndTheExternalFlag() throws Exception {
        WorkItem inside = item("wi-1", 1, "Inside");
        WorkItem ghost = item("wi-9", 9, "Elsewhere");
        when(dependencyService.graph("proj-1", "wi-1")).thenReturn(new WorkItemDependencyService.Graph(
                List.of(new WorkItemDependencyService.GraphNode(inside, "open", false),
                        new WorkItemDependencyService.GraphNode(ghost, "in_progress", true)),
                List.of(new WorkItemDependencyService.GraphEdge("dep-1", "wi-9", "wi-1", "BLOCKS"))));

        mockMvc.perform(get(BASE + "/dependency-graph").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].external").value(false))
                .andExpect(jsonPath("$.nodes[1].external").value(true))
                .andExpect(jsonPath("$.nodes[1].statusCategory").value("in_progress"))
                .andExpect(jsonPath("$.edges[0].from").value("wi-9"))
                .andExpect(jsonPath("$.edges[0].to").value("wi-1"));
    }

    // --- criteria ---

    private WorkItemCriterion criterion(String id, int position, boolean met) {
        WorkItemCriterion c = new WorkItemCriterion();
        c.setId(id);
        c.setPosition(position);
        c.setText("Text " + id);
        c.setKind("manual");
        c.setExternalRef("AC-1");
        c.setMet(met);
        return c;
    }

    @Test
    void putCriteriaBindsTheArrayBodyAndReturnsRowsWithIds() throws Exception {
        when(criteriaService.replaceAll(eq("proj-1"), eq("wi-1"), any()))
                .thenReturn(List.of(criterion("c-1", 0, false)));

        mockMvc.perform(put(BASE + "/criteria").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"text\":\"Works\",\"kind\":\"manual\",\"externalRef\":\"AC-1\"},{\"text\":\"Fast\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].criterionId").value("c-1"))
                .andExpect(jsonPath("$[0].position").value(0))
                .andExpect(jsonPath("$[0].met").value(false))
                .andExpect(jsonPath("$[0].externalRef").value("AC-1"));

        verify(criteriaService).replaceAll(eq("proj-1"), eq("wi-1"), org.mockito.ArgumentMatchers.argThat(
                inputs -> inputs.size() == 2 && "Works".equals(inputs.get(0).text())
                        && "manual".equals(inputs.get(0).kind()) && inputs.get(1).kind() == null));
    }

    @Test
    void patchCriterionReturnsTheCriterionAndTotals() throws Exception {
        WorkItemCriterion met = criterion("c-1", 0, true);
        met.setMetAt(OffsetDateTime.parse("2026-11-01T12:00:00Z"));
        met.setMetByLabel("claude");
        when(criteriaService.patch(eq("proj-1"), eq("wi-1"), eq("c-1"), isNull(), isNull(), eq(true),
                eq("CI green"), any(ProjectActor.class)))
                .thenReturn(new WorkItemCriteriaService.PatchResult(met, 1, 2));

        mockMvc.perform(patch(BASE + "/criteria/c-1").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"met\":true,\"evidence\":\"CI green\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criterion.met").value(true))
                .andExpect(jsonPath("$.criterion.metByLabel").value("claude"))
                .andExpect(jsonPath("$.criteriaMet").value(1))
                .andExpect(jsonPath("$.criteriaTotal").value(2));
    }

    // --- agent sessions ---

    @Test
    void putAgentSessionBindsTheBodyAndTheSessionKeyFromThePath() throws Exception {
        WorkItemAgentSession s = new WorkItemAgentSession();
        s.setSessionKey("run 1");
        s.setAgentLabel("claude-code");
        s.setMode("DELEGATE");
        s.setActivity("NEEDS_INPUT");
        s.setNote("Which database?");
        s.setStartedAt(OffsetDateTime.parse("2026-11-01T10:00:00Z"));
        s.setLastHeartbeatAt(OffsetDateTime.parse("2026-11-01T11:00:00Z"));
        when(sessionService.upsert(eq("proj-1"), eq("wi-1"), eq("run 1"), eq("claude-code"), isNull(),
                eq("NEEDS_INPUT"), eq("Which database?"), isNull())).thenReturn(s);

        mockMvc.perform(put(BASE + "/agent-sessions/{key}", "run 1").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentLabel\":\"claude-code\",\"activity\":\"NEEDS_INPUT\","
                                + "\"note\":\"Which database?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionKey").value("run 1"))
                .andExpect(jsonPath("$.activity").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.note").value("Which database?"))
                .andExpect(jsonPath("$.mode").value("DELEGATE"));
    }

    @Test
    void anUnknownActivityIsRejectedAtBinding() throws Exception {
        mockMvc.perform(put(BASE + "/agent-sessions/k").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentLabel\":\"claude\",\"activity\":\"NAPPING\"}"))
                .andExpect(status().is4xxClientError());
    }
}
