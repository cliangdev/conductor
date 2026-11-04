package com.conductor.v2.controller;

import com.conductor.creative.CreativeEditReadService;
import com.conductor.creative.PostContentChange;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The wire shape of the three read APIs behind slide-text edits (a standalone MockMvc over the real controllers). */
class CreativeEditsControllerTest {

    private CreativeEditReadService readService;
    private MockMvc mockMvc;
    private final User user = new User();

    @BeforeEach
    void setUp() {
        user.setId("u-1");
        readService = Mockito.mock(CreativeEditReadService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CreativeEditsController(readService),
                new WorkItemContentChangesController(readService)).setControllerAdvice(new GlobalExceptionHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void affectedPostsAreListedWithTheirOutcomeAndAcceptAPostIdFilter() throws Exception {
        when(readService.affectedPosts(eq("p"), eq("c"), eq("post-2"), eq(user))).thenReturn(
                new CreativeEditReadService.AffectedPosts(List.of(
                new CreativeEditReadService.AffectedPost("post-1", "RE-101", "One", "APPROVED", "Approved", "back-to-review"),
                new CreativeEditReadService.AffectedPost("post-2", "RE-102", "Two", "IN_REVIEW", "In Review", "reviewer-fix")),
                "Post RE-101 uses this Creative and is approved or scheduled. Ask an editor to make this change."));

        mockMvc.perform(get("/projects/p/marketing/creatives/c/affected-posts").param("postId", "post-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts.length()").value(2))
                .andExpect(jsonPath("$.posts[0].workItemId").value("post-1"))
                .andExpect(jsonPath("$.posts[0].displayId").value("RE-101"))
                .andExpect(jsonPath("$.posts[0].statusLabel").value("Approved"))
                .andExpect(jsonPath("$.posts[0].outcome").value("back-to-review"))
                .andExpect(jsonPath("$.posts[1].outcome").value("reviewer-fix"))
                .andExpect(jsonPath("$.blockedReason").value(
                        "Post RE-101 uses this Creative and is approved or scheduled. Ask an editor to make this change."));
    }

    @Test
    void renderAvailabilityReportsTheRuntimeTargetAndUnknownDaemonLiveness() throws Exception {
        when(readService.renderAvailability("p", "c", user))
                .thenReturn(new CreativeEditReadService.RenderAvailability("target", "byo-test", null));
        when(readService.renderAvailability("p", "d", user))
                .thenReturn(new CreativeEditReadService.RenderAvailability("self-hosted", null, null));

        mockMvc.perform(get("/projects/p/marketing/creatives/c/render-availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtime").value("target"))
                .andExpect(jsonPath("$.targetName").value("byo-test"));
        mockMvc.perform(get("/projects/p/marketing/creatives/d/render-availability"))
                .andExpect(jsonPath("$.runtime").value("self-hosted"))
                .andExpect(jsonPath("$.daemonOnline").doesNotExist());
    }

    @Test
    void contentChangesCarryTheDiffTheNamesAndBothRounds() throws Exception {
        PostContentChange change = new PostContentChange();
        change.setId("chg-1");
        change.setCreativeId("c");
        change.setRevisionId("rv-1");
        change.setSource("REVIEWER");
        change.setActorUserId("u-2");
        change.setFromStatus("IN_REVIEW");
        change.setToStatus("IN_REVIEW");
        change.setReviewRoundBefore(2);
        change.setReviewRoundAfter(2);
        ObjectMapper mapper = new ObjectMapper();
        change.setTextDiff(mapper.createArrayNode().add(mapper.createObjectNode().put("slide", 3)
                .put("path", "texts[0].t").put("old", "Teh").put("new", "The")));
        change.setCreatedAt(java.time.OffsetDateTime.parse("2026-11-02T10:00:00Z"));
        when(readService.contentChanges("p", "w", user)).thenReturn(List.of(
                new CreativeEditReadService.ChangeView(change, 4, "158a", "Rita Reviewer")));

        mockMvc.perform(get("/projects/p/work-items/w/content-changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].id").value("chg-1"))
                .andExpect(jsonPath("$.changes[0].creativeDisplayId").value("158a"))
                .andExpect(jsonPath("$.changes[0].revisionNo").value(4))
                .andExpect(jsonPath("$.changes[0].source").value("REVIEWER"))
                .andExpect(jsonPath("$.changes[0].actorName").value("Rita Reviewer"))
                .andExpect(jsonPath("$.changes[0].reviewRoundBefore").value(2))
                .andExpect(jsonPath("$.changes[0].reviewRoundAfter").value(2))
                .andExpect(jsonPath("$.changes[0].textDiff[0].slide").value(3))
                .andExpect(jsonPath("$.changes[0].textDiff[0].old").value("Teh"))
                .andExpect(jsonPath("$.changes[0].textDiff[0].new").value("The"));
    }

    @Test
    void aNonMemberOrUnknownWorkItemIsA404() throws Exception {
        when(readService.contentChanges("p", "nope", user)).thenThrow(new EntityNotFoundException("Work Item not found"));

        mockMvc.perform(get("/projects/p/work-items/nope/content-changes")).andExpect(status().isNotFound());
    }
}
