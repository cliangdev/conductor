package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativeAttachService;
import com.conductor.creative.CreativeExperimentService;
import com.conductor.creative.CreativePerformanceService;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRegistry;
import com.conductor.creative.CreativeRenderDispatchService;
import com.conductor.creative.CreativeRenderService;
import com.conductor.creative.CreativeRevision;
import com.conductor.creative.CreativeRevisionService;
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
    private CreativeRevisionService revisionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        creativeService = Mockito.mock(CreativeService.class);
        renderService = Mockito.mock(CreativeRenderService.class);
        dispatchService = Mockito.mock(CreativeRenderDispatchService.class);
        revisionService = Mockito.mock(CreativeRevisionService.class);
        CreativeController controller = new CreativeController(Mockito.mock(CreativePhotoService.class), creativeService,
                Mockito.mock(CreativeRegistry.class), new ObjectMapper(), renderService,
                Mockito.mock(CreativeAttachService.class), Mockito.mock(CreativePerformanceService.class),
                Mockito.mock(CreativeExperimentService.class), dispatchService, revisionService);
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

    // ── slide-text revisions: the render job's calls and the edit ────────────────────────────────

    private static CreativeRevision revision() {
        CreativeRevision r = new CreativeRevision();
        r.setId("rv-1");
        r.setProjectId("proj-1");
        r.setCreativeId("cr-1");
        r.setRevisionNo(2);
        r.setCreativeVersion(3);
        r.setBaseVersion(3);
        r.setStatus(CreativeRevision.STATUS_PENDING);
        r.setSource(CreativeRevision.SOURCE_EDITOR);
        ObjectMapper om = new ObjectMapper();
        r.setSpec(om.createObjectNode().put("id", "s"));
        r.setTextDiff(om.createArrayNode());
        r.setWorkflowRunId("run-1");
        r.setCreatedAt(OffsetDateTime.now());
        return r;
    }

    @Test
    void theRevisionsRunTokenReadsCompletesAndFailsItAsARunCaller() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();
        RenderCaller run = new RenderCaller.RunCaller("run-1", "proj-1", "cr-1");
        when(revisionService.getDetail("proj-1", "cr-1", "rv-1", run))
                .thenReturn(new CreativeRevisionService.RevisionDetail(revision(), java.util.List.of(2), null, null));
        when(revisionService.complete(eq("proj-1"), eq("cr-1"), eq("rv-1"), any(), eq(run))).thenReturn(revision());
        when(revisionService.fail(eq("proj-1"), eq("cr-1"), eq("rv-1"), any(), eq(run))).thenReturn(revision());

        mockMvc.perform(get(BASE + "/revisions/rv-1")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.changedSlides[0]").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.baseVersion").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.workflowRunId").value("run-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.spec.id").value("s"));
        mockMvc.perform(post(BASE + "/revisions/rv-1/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slideMedia\":{\"9x16\":{\"2\":\"m-1\"}},\"checks\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/revisions/rv-1/fail").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"error\":\"boom\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aRunTokenThatIsNotThisCreativesRenderRunIsRefusedOnTheRevisionEndpoints() throws Exception {
        asRun("proj-1", "run-1"); // isRenderRunFor defaults to false
        mockMvc.perform(get(BASE + "/revisions/rv-1")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/revisions/rv-1/complete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slideMedia\":{}}")).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/revisions/rv-1/fail").contentType(MediaType.APPLICATION_JSON)
                .content("{\"error\":\"x\"}")).andExpect(status().isForbidden());

        asRun("proj-OTHER", "run-1");
        mockMvc.perform(get(BASE + "/revisions/rv-1")).andExpect(status().isForbidden());

        verify(revisionService, never()).getDetail(any(), any(), any(), any());
        verify(revisionService, never()).complete(any(), any(), any(), any(), any());
        verify(revisionService, never()).fail(any(), any(), any(), any(), any());
    }

    @Test
    void anotherRunsRevisionIsRefusedByTheServiceAndSurfacesAs403() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();
        when(revisionService.getDetail(any(), any(), any(), any(RenderCaller.class)))
                .thenThrow(new ForbiddenException("This run token did not request revision rv-1"));

        mockMvc.perform(get(BASE + "/revisions/rv-1")).andExpect(status().isForbidden());
    }

    @Test
    void aProjectEditorsOwnKeyUsesTheRevisionEndpointsAsAUserCaller() throws Exception {
        com.conductor.entity.User user = userPrincipal();
        SecurityContextHolder.getContext().setAuthentication(new com.conductor.security.UserApiKeyAuthenticationToken(user));
        when(revisionService.complete(eq("proj-1"), eq("cr-1"), eq("rv-1"), any(), eq(new RenderCaller.UserCaller(user))))
                .thenReturn(revision());

        mockMvc.perform(post(BASE + "/revisions/rv-1/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slideMedia\":{\"9x16\":{\"2\":\"m-1\"}}}"))
                .andExpect(status().isOk());

        verify(revisionService).complete(eq("proj-1"), eq("cr-1"), eq("rv-1"), any(), eq(new RenderCaller.UserCaller(user)));
    }

    @Test
    void theEditEndpointIs202AndTagsAnApiKeyCallerAsMcpAndABrowserSessionAsEditor() throws Exception {
        com.conductor.entity.User user = userPrincipal();
        when(revisionService.edit(eq("proj-1"), eq("cr-1"), eq(3), any(), any(), eq(user)))
                .thenReturn(new CreativeRevisionService.EditResult(revision(),
                        new CreativeRenderDispatchService.RenderRunView("wf-1", "run-1", "PENDING_LOCAL_PICKUP",
                                "AWAITING_RUNNER", null, null, null)));
        String body = "{\"version\":3,\"edits\":[{\"slide\":2,\"path\":\"texts[0].t\",\"text\":\"Hi\"}]}";

        SecurityContextHolder.getContext().setAuthentication(new com.conductor.security.UserApiKeyAuthenticationToken(user));
        mockMvc.perform(post(BASE + "/slide-text-edits").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.revision.id").value("rv-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.renderRun.runId").value("run-1"));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));
        mockMvc.perform(post(BASE + "/slide-text-edits").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<java.util.List<com.conductor.creative.SlideshowTextFields.Edit>> edits =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.ArgumentCaptor<String> source = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(revisionService, org.mockito.Mockito.times(2)).edit(eq("proj-1"), eq("cr-1"), eq(3), edits.capture(),
                source.capture(), eq(user));
        org.assertj.core.api.Assertions.assertThat(source.getAllValues()).containsExactly("MCP", "EDITOR");
        org.assertj.core.api.Assertions.assertThat(edits.getValue()).containsExactly(
                new com.conductor.creative.SlideshowTextFields.Edit(2, "texts[0].t", "Hi"));
    }

    @Test
    void aRunTokenCannotEditSlideText() throws Exception {
        asRun("proj-1", "run-1");
        runIsTheRenderRunForCr1();

        mockMvc.perform(post(BASE + "/slide-text-edits").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"edits\":[{\"slide\":2,\"path\":\"texts[0].t\",\"text\":\"Hi\"}]}"))
                .andExpect(status().isForbidden());

        verify(revisionService, never()).edit(any(), any(), any(), any(), any(), any());
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
