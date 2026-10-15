package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelDetail;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Window;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MarketingOverviewController}: membership gating (any member reads, only
 * ADMIN/CREATOR write), request validation and the target set/clear round trip. The numbers themselves are
 * proven in {@code MarketingOverviewQueryServiceIntegrationTest}; the query service is mocked here.
 */
@WebMvcTest(MarketingOverviewController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingOverviewControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v2/projects/" + PROJECT_ID + "/marketing";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MarketingOverviewQueryService queryService;
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

    private void memberWithRole(MemberRole role) {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));
    }

    private static Window window() {
        return new Window(LocalDate.of(2026, 9, 18), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 8, 21),
                LocalDate.of(2026, 9, 17));
    }

    private static ChannelView webChannel(TargetView target) {
        MetricView visitors = new MetricView("visitors", "Visitors", MetricCatalog.Unit.COUNT, 70.0, 35.0, 35.0, 1.0,
                false, 2, target, List.of(new MarketingOverviewQueryService.Point(LocalDate.of(2026, 10, 14), 10.0, true)));
        return new ChannelView("WEB", "Web", "LIVE", null,
                List.of(new MarketingOverviewQueryService.ConnectionView("c1", "posthog", "Site", "LIVE")), "posthog",
                List.of(visitors), LocalDate.of(2026, 10, 12));
    }

    // ---- reads ----

    @Test
    void member_readsTheOverview() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(queryService.overview(PROJECT_ID, "7d")).thenReturn(new Overview(window(),
                List.of(new MarketingOverviewQueryService.FunnelStageView("web_visitors", "Web visitors", 70.0, 35.0,
                        35.0, "WEB", true, null, LocalDate.of(2026, 10, 12))),
                List.of(webChannel(new TargetView(500, "UP")))));

        mockMvc.perform(get(BASE + "/overview").param("window", "7d")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.to").value("2026-10-14"))
                .andExpect(jsonPath("$.funnel[0].key").value("web_visitors"))
                .andExpect(jsonPath("$.funnel[0].available").value(true))
                .andExpect(jsonPath("$.channels[0].channel").value("WEB"))
                .andExpect(jsonPath("$.channels[0].status").value("LIVE"))
                .andExpect(jsonPath("$.channels[0].connectHint.connectorId").value("posthog"))
                .andExpect(jsonPath("$.channels[0].metrics[0].metric").value("visitors"))
                .andExpect(jsonPath("$.channels[0].metrics[0].unit").value("COUNT"))
                .andExpect(jsonPath("$.channels[0].metrics[0].provisionalDays").value(2))
                .andExpect(jsonPath("$.channels[0].metrics[0].target.value").value(500.0))
                .andExpect(jsonPath("$.channels[0].dataCompleteThrough").value("2026-10-12"))
                .andExpect(jsonPath("$.funnel[0].dataCompleteThrough").value("2026-10-12"))
                .andExpect(jsonPath("$.channels[0].metrics[0].series[0].day").value("2026-10-14"))
                .andExpect(jsonPath("$.channels[0].metrics[0].series[0].provisional").value(true));
    }

    @Test
    void nonMember_cannotReadTheOverview() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get(BASE + "/overview").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        mockMvc.perform(get(BASE + "/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void badWindow_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(queryService.overview(PROJECT_ID, "90d"))
                .thenThrow(new BusinessException("Unknown window '90d'; expected [7d, 28d]"));

        mockMvc.perform(get(BASE + "/overview").param("window", "90d")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown window '90d'; expected [7d, 28d]"));
    }

    @Test
    void channelDetail_returnsDimensionsAndUnknownChannelIs400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(queryService.channel(PROJECT_ID, "web", "28d")).thenReturn(new ChannelDetail(window(),
                webChannel(null),
                List.of(new MarketingOverviewQueryService.DimensionTable("referrers", "Top referrers",
                        List.of(new MarketingOverviewQueryService.ColumnView("sessions", "Sessions",
                                MetricCatalog.Unit.COUNT)),
                        List.of(new MarketingOverviewQueryService.DimensionRowView("(direct)", "(direct)",
                                List.of(12.0))))),
                List.of()));
        when(queryService.channel(PROJECT_ID, "nope", "28d"))
                .thenThrow(new BusinessException("Unknown channel 'nope'"));

        mockMvc.perform(get(BASE + "/channels/web").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel.channel").value("WEB"))
                .andExpect(jsonPath("$.dimensions[0].key").value("referrers"))
                .andExpect(jsonPath("$.dimensions[0].rows[0].values[0]").value(12.0));

        mockMvc.perform(get(BASE + "/channels/nope").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void socialChannelDetail_returnsTopPosts() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        var post = new com.conductor.marketing.insights.MarketingInsightsQueryService.Post("wi-1", "COND-9",
                "Launch reel", "MARKETING", "caption", "t-1", "instagram", "REEL", "@brand", "https://example.com/p",
                null, 1200L, 90L, 5L, 3L, 2L, 0.083, 0.61, null);
        when(queryService.channel(PROJECT_ID, "social", "28d")).thenReturn(new ChannelDetail(window(),
                new ChannelView("SOCIAL", "Social", "LIVE", null, List.of(), "meta", List.of(),
                        LocalDate.of(2026, 10, 14)),
                List.of(), List.of(post)));

        mockMvc.perform(get(BASE + "/channels/social").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topPosts.length()").value(1))
                .andExpect(jsonPath("$.topPosts[0].displayId").value("COND-9"))
                .andExpect(jsonPath("$.topPosts[0].platform").value("instagram"))
                .andExpect(jsonPath("$.topPosts[0].views").value(1200))
                .andExpect(jsonPath("$.topPosts[0].engagementRate").value(0.083));
    }

    // ---- targets ----

    @Test
    void reviewer_cannotSetOrClearATarget() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(put(BASE + "/targets/WEB/visitors")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\": 500}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE + "/targets/WEB/visitors").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());

        verify(queryService, never()).setTarget(anyString(), anyString(), anyString(), any(), any(), any());
        verify(queryService, never()).clearTarget(anyString(), anyString(), anyString());
    }

    @Test
    void creator_setsATargetRecordingTheirIdAndDeleteMakesItNullAgain() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        AtomicReference<TargetView> stored = new AtomicReference<>();
        when(queryService.setTarget(eq(PROJECT_ID), eq("WEB"), eq("visitors"), eq(500.0), eq("DOWN"), eq("user-1")))
                .thenAnswer(inv -> {
                    stored.set(new TargetView(500.0, "DOWN"));
                    return stored.get();
                });
        doAnswer(inv -> {
            stored.set(null);
            return null;
        }).when(queryService).clearTarget(PROJECT_ID, "WEB", "visitors");
        when(queryService.overview(PROJECT_ID, "28d")).thenAnswer(inv ->
                new Overview(window(), List.of(), List.of(webChannel(stored.get()))));

        mockMvc.perform(put(BASE + "/targets/WEB/visitors")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\": 500, \"direction\": \"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(500.0))
                .andExpect(jsonPath("$.direction").value("DOWN"));

        mockMvc.perform(get(BASE + "/overview").header("Authorization", "Bearer valid-token"))
                .andExpect(jsonPath("$.channels[0].metrics[0].target.value").value(500.0));

        mockMvc.perform(delete(BASE + "/targets/WEB/visitors").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE + "/overview").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].metrics[0].target").doesNotExist());
    }

    @Test
    void negativeTarget_returns400BeforeReachingTheService() throws Exception {
        memberWithRole(MemberRole.ADMIN);

        mockMvc.perform(put(BASE + "/targets/WEB/visitors")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\": -1}"))
                .andExpect(status().isBadRequest());

        verify(queryService, never()).setTarget(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void nonNumericOrMissingTarget_returns400() throws Exception {
        memberWithRole(MemberRole.ADMIN);

        mockMvc.perform(put(BASE + "/targets/WEB/visitors")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\": \"lots\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/targets/WEB/visitors")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownMetric_returns400WithThePlainMessage() throws Exception {
        memberWithRole(MemberRole.ADMIN);
        when(queryService.setTarget(eq(PROJECT_ID), eq("WEB"), eq("bogus"), any(), any(), any()))
                .thenThrow(new BusinessException("Unknown metric 'bogus' for channel WEB"));

        mockMvc.perform(put(BASE + "/targets/WEB/bogus")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown metric 'bogus' for channel WEB"));
    }
}
