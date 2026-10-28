package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.ReviewerService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** @WebMvcTest slice for the {@code dueBefore} parameter of {@code GET /work-items}: date binding and a bad date. */
@WebMvcTest(WorkItemController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class WorkItemControllerListTest {

    private static final String URL = "/api/v2/projects/proj-1/work-items";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WorkItemService workItemService;
    @MockitoBean private WorkItemWorkflowService workItemWorkflowService;
    @MockitoBean private ReviewerService reviewerService;
    @MockitoBean private WorkItemListEnricher listEnricher;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

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
        when(workItemService.listWorkItemEntities(any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(workItemService.unresolvedCommentCounts(anyList())).thenReturn(Map.of());
        when(workItemService.externalLinks(anyList())).thenReturn(Map.of());
        when(reviewerService.reviewersByWorkItem(anyList())).thenReturn(Map.of());
    }

    @Test
    void dueBefore_isBoundAsADateAndPassedToTheService() throws Exception {
        mockMvc.perform(get(URL).param("dueBefore", "2026-10-20").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());

        verify(workItemService).listWorkItemEntities(eq("proj-1"), isNull(), isNull(), isNull(), isNull(),
                eq(LocalDate.of(2026, 10, 20)), any());
    }

    @Test
    void dueBefore_omitted_isNull() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer valid-token")).andExpect(status().isOk());

        verify(workItemService).listWorkItemEntities(eq("proj-1"), isNull(), isNull(), isNull(), isNull(),
                isNull(), any());
    }

    @Test
    void dueBefore_withABadDate_returns400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(get(URL).param("dueBefore", "next tuesday").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());

        verify(workItemService, never()).listWorkItemEntities(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void enrich_isOffByDefaultSoApiAndMcpCallersGetTheSamePayloadAsBefore() throws Exception {
        mockMvc.perform(get(URL).header("Authorization", "Bearer valid-token")).andExpect(status().isOk());
        verify(listEnricher, never()).enrich(any(), any());
    }

    @Test
    void enrich_true_asksTheEnricherForThePage() throws Exception {
        mockMvc.perform(get(URL).param("enrich", "true").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
        verify(listEnricher).enrich(eq("proj-1"), any());
    }

    @Test
    void creativeIdAndMediaIdAreScopedToTheProject() throws Exception {
        mockMvc.perform(get(URL).param("creativeId", "c1").param("mediaId", "m1")
                .header("Authorization", "Bearer valid-token")).andExpect(status().isOk());
        verify(listEnricher).idsUsing("proj-1", "c1", "m1");
    }

    @Test
    void delete_doesNotConfirmLiveByDefault() throws Exception {
        mockMvc.perform(delete(URL + "/wi-1").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());
        verify(workItemService).deleteWorkItem(eq("proj-1"), eq("wi-1"), any(), eq(false));
    }

    @Test
    void delete_passesAnExplicitConfirmLive() throws Exception {
        mockMvc.perform(delete(URL + "/wi-1").param("confirmLive", "true").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());
        verify(workItemService).deleteWorkItem(eq("proj-1"), eq("wi-1"), any(), eq(true));
    }
}
