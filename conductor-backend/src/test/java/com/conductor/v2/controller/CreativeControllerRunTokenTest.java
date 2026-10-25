package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativeAttachService;
import com.conductor.creative.CreativeExperimentService;
import com.conductor.creative.CreativePerformanceService;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRegistry;
import com.conductor.creative.CreativeRenderDispatchService;
import com.conductor.creative.CreativeRenderService;
import com.conductor.creative.CreativeService;
import com.conductor.creative.RenderCaller;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.security.WorkflowRunAuthenticationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a workflow run's token ({@code WorkflowRunAuthenticationToken}, the key the render job holds) may do
 * on the Creative endpoints: only the render ones, and only for its own run. Anything else is a clean 403,
 * never a ClassCastException-driven 500. A standalone MockMvc over the real controller and advice.
 */
class CreativeControllerRunTokenTest {

    private static final String BASE = "/projects/proj-1/marketing/creatives/cr-1";

    private CreativeService creativeService;
    private CreativeRenderService renderService;
    private CreativeRenderDispatchService dispatchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        creativeService = Mockito.mock(CreativeService.class);
        renderService = Mockito.mock(CreativeRenderService.class);
        dispatchService = Mockito.mock(CreativeRenderDispatchService.class);
        CreativeController controller = new CreativeController(Mockito.mock(CreativePhotoService.class), creativeService,
                Mockito.mock(CreativeRegistry.class), new ObjectMapper(), renderService,
                Mockito.mock(CreativeAttachService.class), Mockito.mock(CreativePerformanceService.class),
                Mockito.mock(CreativeExperimentService.class), dispatchService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void asRun(String projectId, String runId) {
        SecurityContextHolder.getContext().setAuthentication(new WorkflowRunAuthenticationToken(projectId, runId));
    }

    /** The token's run is the render workflow's run for cr-1 of proj-1. */
    private void runIsTheRenderRunForCr1() {
        when(dispatchService.isRenderRunFor("run-1", "proj-1", "cr-1")).thenReturn(true);
    }

    @Test
    void aRunTokenOnANonRenderEndpointIsA403NotA500() throws Exception {
        asRun("proj-1", "run-1");

        mockMvc.perform(patch(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"headline\":\"x\"}"))
                .andExpect(status().isForbidden());

        verify(creativeService, never()).patchCreative(any(), any(), any(), any());
    }

    @Test
    void anyOtherNonUserPrincipalGetsACleanForbiddenToo() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("proj-1", null));

        mockMvc.perform(patch(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"headline\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/renders"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRunTokenCannotDispatchRenders() throws Exception {
        asRun("proj-1", "run-1");

        mockMvc.perform(post(BASE + "/render-runs")).andExpect(status().isForbidden());

        verify(dispatchService, never()).dispatch(any(), any());
    }

    @Test
    void postingARenderWithAnotherRunsIdIsRefused() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();

        mockMvc.perform(post(BASE + "/renders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renderer\":\"workflow\",\"workflowRunId\":\"run-OTHER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/renders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renderer\":\"workflow\"}"))
                .andExpect(status().isForbidden());

        verify(renderService, never()).requestRender(any(), any(), any(), any(RenderCaller.class));
    }

    @Test
    void aRunTokenForAnotherProjectIsRefusedOnTheRenderEndpoints() throws Exception {
        asRun("proj-OTHER", "run-1");

        mockMvc.perform(post(BASE + "/renders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renderer\":\"workflow\",\"workflowRunId\":\"run-1\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
    }

    @Test
    void frameUploadCompleteAndFailAreRefusedOnARenderTheRunDidNotRequest() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();
        doThrow(new ForbiddenException("not yours")).when(renderService)
                .requireRenderOfRun("proj-1", "cr-1", "rd-9", "run-1");

        mockMvc.perform(put(BASE + "/renders/rd-9/frames/9x16?width=1080&height=1920")
                        .contentType("image/jpeg").content(new byte[]{1, 2, 3}))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(BASE + "/renders/rd-9/frames/9x16/poster")
                        .contentType("image/jpeg").content(new byte[]{1, 2, 3}))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/renders/rd-9/complete").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/renders/rd-9/fail").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/renders/rd-9")).andExpect(status().isForbidden());

        verify(renderService, never()).putFrame(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any(),
                any(), any(), any(RenderCaller.class));
        verify(renderService, never()).completeRender(any(), any(), any(), any(), any(RenderCaller.class));
        verify(renderService, never()).failRender(any(), any(), any(), any(), any(RenderCaller.class));
    }

    @Test
    void aRunTokenReadsItsProjectsCreativeWithoutAUser() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();
        Creative creative = new Creative();
        creative.setId("cr-1");
        creative.setProjectId("proj-1");
        creative.setKind(Creative.KIND_MOTION);
        creative.setState(Creative.STATE_DRAFT);
        creative.setLayout("stacked");
        creative.setTheme(Creative.THEME_DARK);
        creative.setLockup(Creative.LOCKUP_CHIP);
        ObjectMapper om = new ObjectMapper();
        creative.setPlacements(om.createArrayNode());
        creative.setSequence(om.createArrayNode());
        creative.setTypeOverrides(om.createObjectNode());
        creative.setCreatedAt(OffsetDateTime.now());
        creative.setUpdatedAt(OffsetDateTime.now());
        when(creativeService.getCreative(eq("proj-1"), eq("cr-1"), any(RenderCaller.class)))
                .thenReturn(new CreativeService.CreativeView(creative, null));

        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        verify(creativeService).getCreative("proj-1", "cr-1", new RenderCaller.RunCaller("run-1", "proj-1", "cr-1"));
    }

    @Test
    void theSingleCreativeReadNeverLooksUpTheRenderRun() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(userPrincipal(), null));
        when(creativeService.getCreative(any(), any(), any(RenderCaller.class)))
                .thenThrow(new ForbiddenException("stop"));

        mockMvc.perform(get(BASE));

        verify(dispatchService, never()).latestRun(any(), any());
    }

    // ── a run token is scoped to the Creative its run was dispatched for ─────────────────────────

    @Test
    void aRunTokenForCreativeAIsRefusedOnCreativeBOnEveryRenderEndpoint() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();
        String other = "/projects/proj-1/marketing/creatives/cr-2";

        mockMvc.perform(get(other)).andExpect(status().isForbidden());
        mockMvc.perform(post(other + "/renders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"renderer\":\"workflow\",\"workflowRunId\":\"run-1\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get(other + "/renders/rd-1")).andExpect(status().isForbidden());
        mockMvc.perform(put(other + "/renders/rd-1/frames/9x16?width=1080&height=1920")
                .contentType("image/jpeg").content(new byte[]{1})).andExpect(status().isForbidden());
        mockMvc.perform(put(other + "/renders/rd-1/frames/9x16/poster")
                .contentType("image/jpeg").content(new byte[]{1})).andExpect(status().isForbidden());
        mockMvc.perform(post(other + "/renders/rd-1/complete").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(other + "/renders/rd-1/fail").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"x\"}")).andExpect(status().isForbidden());

        verify(creativeService, never()).getCreative(any(), any(), any(RenderCaller.class));
        verify(renderService, never()).requestRender(any(), any(), any(), any(RenderCaller.class));
        verify(renderService, never()).getRender(any(), any(), any(), any(RenderCaller.class));
        verify(renderService, never()).completeRender(any(), any(), any(), any(), any(RenderCaller.class));
        verify(renderService, never()).failRender(any(), any(), any(), any(), any(RenderCaller.class));
    }

    @Test
    void aRunTokenOfANonRenderWorkflowIsRefusedEvenOnItsOwnWorkflowRunId() throws Exception {
        asRun("proj-1", "run-1");
        // isRenderRunFor defaults to false: the run belongs to some other workflow.

        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/renders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"renderer\":\"workflow\",\"workflowRunId\":\"run-1\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/renders/rd-1/complete").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verify(renderService, never()).requestRender(any(), any(), any(), any(RenderCaller.class));
    }

    @Test
    void theLatestRenderRunIsNotForARunToken() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();

        mockMvc.perform(get(BASE + "/render-runs/latest")).andExpect(status().isForbidden());

        verify(dispatchService, never()).latestRun(any(), any());
    }

    @Test
    void theLatestRenderRunIs204WhenNoneAnd200WithTheRunOtherwise() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(userPrincipal(), null));
        when(dispatchService.latestRun("proj-1", "cr-1")).thenReturn(java.util.Optional.empty());
        mockMvc.perform(get(BASE + "/render-runs/latest")).andExpect(status().isNoContent());

        when(dispatchService.latestRun("proj-1", "cr-1")).thenReturn(java.util.Optional.of(
                new CreativeRenderDispatchService.RenderRunView("wf-1", "run-9", "RUNNING", "AWAITING_RUNNER",
                        null, null, null)));
        mockMvc.perform(get(BASE + "/render-runs/latest")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.runId").value("run-9"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.waitReason").value("AWAITING_RUNNER"));
    }

    @Test
    void theLatestRenderRunReportsWhereItRuns() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(userPrincipal(), null));
        when(dispatchService.latestRun("proj-1", "cr-1")).thenReturn(java.util.Optional.of(
                new CreativeRenderDispatchService.RenderRunView("wf-1", "run-9", "RUNNING", null,
                        null, null, null, "target", "acme-run")));
        mockMvc.perform(get(BASE + "/render-runs/latest")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.runtime").value("target"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.targetName").value("acme-run"));

        when(dispatchService.latestRun("proj-1", "cr-1")).thenReturn(java.util.Optional.of(
                new CreativeRenderDispatchService.RenderRunView("wf-1", "run-8", "RUNNING", "AWAITING_RUNNER",
                        null, null, null)));
        mockMvc.perform(get(BASE + "/render-runs/latest")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.runtime").value("self-hosted"));
    }

    private static com.conductor.entity.User userPrincipal() {
        com.conductor.entity.User user = new com.conductor.entity.User();
        user.setId("u-1");
        return user;
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
