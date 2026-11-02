package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkItemHealthUpdate;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.DeliveryOverviewService;
import com.conductor.service.JwtService;
import com.conductor.service.ProgressRollup.Progress;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.ThroughputForecastService;
import com.conductor.service.ThroughputForecastService.Fit;
import com.conductor.service.ThroughputForecastService.Forecast;
import com.conductor.service.ThroughputForecastService.Mode;
import com.conductor.service.ThroughputForecastService.State;
import com.conductor.service.WorkItemHealthService;
import com.conductor.service.WorkItemSignalService.Signals;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for the forecast, health-update and delivery-overview controllers: binding (including the
 * body-less confirm), role checks, error mapping and response shape. The rules are in the service tests.
 */
@WebMvcTest({WorkItemForecastController.class, WorkItemHealthController.class, DeliveryOverviewController.class})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class WorkItemForecastHealthControllersTest {

    private static final String BASE = "/api/v2/projects/proj-1/work-items/wi-1";
    private static final String AUTH = "Bearer valid-token";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ThroughputForecastService forecastService;
    @MockitoBean private WorkItemHealthService healthService;
    @MockitoBean private DeliveryOverviewService overviewService;
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
        when(healthService.confirmerNames(any())).thenReturn(Map.of("user-1", "User"));
    }

    private void asRole(MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1")).thenReturn(Optional.of(member));
    }

    private static WorkItemHealthUpdate update(String id, boolean confirmed) {
        WorkItemHealthUpdate u = new WorkItemHealthUpdate();
        u.setId(id);
        u.setHealth("AT_RISK");
        u.setNote("slipping");
        u.setDraftedByLabel("Agent");
        u.setCreatedAt(OffsetDateTime.parse("2026-11-01T10:00:00Z"));
        if (confirmed) {
            u.setConfirmedBy("user-1");
            u.setConfirmedAt(OffsetDateTime.parse("2026-11-01T11:00:00Z"));
        }
        return u;
    }

    // --- forecast ---

    @Test
    void forecastRendersDatesFitsAndTheMode() throws Exception {
        when(forecastService.forecast("proj-1", "wi-1")).thenReturn(new Forecast(State.OK, Mode.THROUGHPUT, 6, 12,
                null, 2.5, LocalDate.of(2026, 11, 22), LocalDate.of(2026, 12, 6), LocalDate.of(2026, 11, 29),
                Fit.TIGHT, null));

        mockMvc.perform(get(BASE + "/forecast").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OK"))
                .andExpect(jsonPath("$.mode").value("THROUGHPUT"))
                .andExpect(jsonPath("$.remaining").value(6))
                .andExpect(jsonPath("$.weeklyThroughputMedian").value(2.5))
                .andExpect(jsonPath("$.p50Date").value("2026-11-22"))
                .andExpect(jsonPath("$.p85Date").value("2026-12-06"))
                .andExpect(jsonPath("$.appetiteEndsOn").value("2026-11-29"))
                .andExpect(jsonPath("$.appetiteFit").value("TIGHT"));
    }

    @Test
    void forecastForAMissingItemIs404() throws Exception {
        when(forecastService.forecast("proj-1", "wi-1")).thenThrow(new EntityNotFoundException("Work Item not found"));

        mockMvc.perform(get(BASE + "/forecast").header("Authorization", AUTH)).andExpect(status().isNotFound());
    }

    @Test
    void forecastForANonMemberIs403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(false);

        mockMvc.perform(get(BASE + "/forecast").header("Authorization", AUTH)).andExpect(status().isForbidden());
        verify(forecastService, never()).forecast(anyString(), anyString());
    }

    // --- health updates ---

    @Test
    void draftTrueRecordsADraftAttributedToTheCaller() throws Exception {
        when(healthService.draft(eq("proj-1"), eq("wi-1"), eq("AT_RISK"), eq("slipping"), anyString()))
                .thenReturn(update("d1", false));

        mockMvc.perform(post(BASE + "/health-updates").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"AT_RISK\",\"note\":\"slipping\",\"draft\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.confirmed").value(false))
                .andExpect(jsonPath("$.draftedByLabel").value("Agent"));
        verify(healthService).draft("proj-1", "wi-1", "AT_RISK", "slipping", "user@example.com");
        verify(healthService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void draftFalseByAPersonRecordsAConfirmedUpdate() throws Exception {
        when(healthService.record(eq("proj-1"), eq("wi-1"), eq("ON_TRACK"), eq("fine"), any(User.class)))
                .thenReturn(update("u1", true));

        mockMvc.perform(post(BASE + "/health-updates").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"ON_TRACK\",\"note\":\"fine\",\"draft\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.confirmed").value(true))
                .andExpect(jsonPath("$.confirmedByName").value("User"));
    }

    @Test
    void aMachineRefusedByTheServiceIs403() throws Exception {
        when(healthService.record(any(), any(), any(), any(), any()))
                .thenThrow(new ForbiddenException("Health is confirmed by a person"));

        mockMvc.perform(post(BASE + "/health-updates").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"ON_TRACK\",\"note\":\"fine\",\"draft\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownHealthOrAMissingDraftFlagIs400() throws Exception {
        mockMvc.perform(post(BASE + "/health-updates").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"GREEN\",\"note\":\"x\",\"draft\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE + "/health-updates").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"ON_TRACK\",\"note\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReviewerCannotWriteHealth() throws Exception {
        asRole(MemberRole.REVIEWER);

        mockMvc.perform(post(BASE + "/health-updates").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"ON_TRACK\",\"note\":\"x\",\"draft\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmWithNoBodyPassesNoOverrides() throws Exception {
        when(healthService.confirm(eq("proj-1"), eq("wi-1"), eq("d1"), any(User.class), isNull(), isNull()))
                .thenReturn(update("d1", true));

        mockMvc.perform(post(BASE + "/health-updates/d1/confirm").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("d1"))
                .andExpect(jsonPath("$.confirmed").value(true));
    }

    @Test
    void confirmWithABodyPassesTheOverrides() throws Exception {
        when(healthService.confirm(eq("proj-1"), eq("wi-1"), eq("d1"), any(User.class), eq("OFF_TRACK"),
                eq("worse"))).thenReturn(update("d1", true));

        mockMvc.perform(post(BASE + "/health-updates/d1/confirm").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"health\":\"OFF_TRACK\",\"note\":\"worse\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void confirmingAnExpiredDraftIs409AndAMissingUpdateIs404() throws Exception {
        when(healthService.confirm(eq("proj-1"), eq("wi-1"), eq("old"), any(), any(), any()))
                .thenThrow(new ConflictException("expired"));
        when(healthService.confirm(eq("proj-1"), eq("wi-1"), eq("nope"), any(), any(), any()))
                .thenThrow(new EntityNotFoundException("Health update not found"));

        mockMvc.perform(post(BASE + "/health-updates/old/confirm").header("Authorization", AUTH))
                .andExpect(status().isConflict());
        mockMvc.perform(post(BASE + "/health-updates/nope/confirm").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsTheHistoryNewestFirst() throws Exception {
        when(healthService.history("proj-1", "wi-1")).thenReturn(List.of(update("d2", false), update("u1", true)));

        mockMvc.perform(get(BASE + "/health-updates").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("d2"))
                .andExpect(jsonPath("$[1].confirmedAt").exists());
    }

    // --- delivery overview ---

    @Test
    void deliveryOverviewRendersTheThreeParts() throws Exception {
        WorkItem prd = new WorkItem();
        prd.setId("prd");
        prd.setProject(project);
        prd.setSequenceNumber(7);
        prd.setTitle("Big feature");
        prd.setType("PRD");
        prd.setWorkflow("ENGINEERING");
        prd.setCurrentStatus("IN_PROGRESS");
        prd.setPriority(1);
        prd.setAppetiteDays(14);
        prd.setCreatedAt(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        prd.setStartedAt(OffsetDateTime.parse("2026-10-05T00:00:00Z"));
        DeliveryOverviewService.ItemSummary summary = new DeliveryOverviewService.ItemSummary(prd, "In progress");
        Forecast forecast = new Forecast(State.INSUFFICIENT_DATA, null, 5, 3, 8, null, null, null, null, null, null);
        WorkItemHealthService.Health health = new WorkItemHealthService.Health("AT_RISK", "slipping",
                OffsetDateTime.parse("2026-11-01T11:00:00Z"), false, null, true);
        Signals signals = new Signals(false, List.of(), false, true, false, null);
        when(overviewService.overview("proj-1")).thenReturn(new DeliveryOverviewService.Overview(
                List.of(new DeliveryOverviewService.ActiveItem(summary, new Progress(10, 4, 40, 6, 2), health,
                        forecast, signals)),
                List.of(),
                new DeliveryOverviewService.Attention(
                        List.of(new DeliveryOverviewService.ItemSummary(prd, "In progress", 2)),
                        List.of(summary), List.of())));

        mockMvc.perform(get("/api/v2/projects/proj-1/delivery-overview").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeItems[0].item.displayId").value("COND-7"))
                .andExpect(jsonPath("$.activeItems[0].item.statusLabel").value("In progress"))
                .andExpect(jsonPath("$.activeItems[0].item.priority").value("P1"))
                .andExpect(jsonPath("$.activeItems[0].progress.percent").value(40))
                .andExpect(jsonPath("$.activeItems[0].health.health").value("AT_RISK"))
                .andExpect(jsonPath("$.activeItems[0].health.atRisk").value(true))
                .andExpect(jsonPath("$.activeItems[0].forecast.state").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.activeItems[0].forecast.needed").value(8))
                .andExpect(jsonPath("$.activeItems[0].signals.stale").value(true))
                .andExpect(jsonPath("$.milestones.length()").value(0))
                .andExpect(jsonPath("$.activeItems[0].item.appetiteDays").value(14))
                .andExpect(jsonPath("$.activeItems[0].item.startedAt").exists())
                .andExpect(jsonPath("$.activeItems[0].item.createdAt").exists())
                .andExpect(jsonPath("$.attention.blocked[0].blockedByCount").value(2))
                .andExpect(jsonPath("$.attention.stale[0].blockedByCount").value(0))
                .andExpect(jsonPath("$.attention.stale[0].id").value("prd"));
    }
}
