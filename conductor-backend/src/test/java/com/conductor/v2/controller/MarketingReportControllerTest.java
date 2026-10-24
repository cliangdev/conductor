package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.marketing.report.MarketingBenchmark;
import com.conductor.marketing.report.MarketingBenchmarkService;
import com.conductor.marketing.report.MarketingReportService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MarketingReportController}: the window parameter reaching the service (and a bad
 * one answering 400), membership gating on the benchmark writes, and a dotted {@code metricRef} surviving the
 * path. The report's numbers are proven in {@code MarketingReportServiceIntegrationTest}.
 */
@WebMvcTest(MarketingReportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingReportControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v2/projects/" + PROJECT_ID + "/marketing";
    private static final String BODY = """
            {"value":0.03,"sourceName":"Example Study","sourceUrl":"https://example.com/study","checkedOn":"2026-10-01"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private MarketingReportService reportService;
    @MockitoBean private MarketingBenchmarkService benchmarkService;
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
    }

    private void memberWithRole(MemberRole role) {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.of(member));
    }

    private static MarketingReportService.Report emptyReport() {
        return new MarketingReportService.Report(
                new MarketingReportService.Window(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 17)),
                OffsetDateTime.parse("2026-10-18T10:00:00Z"), List.of(), List.of(), List.of(),
                new MarketingReportService.Funnel("EVENT", List.of()), List.of(), List.of(), List.of(), List.of(),
                List.of(), new MarketingReportService.Changes(List.of(), List.of(), List.of()));
    }

    // ---- report window ----

    @Test
    void report_at28d_passesTheWindowThrough() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(reportService.report(PROJECT_ID, "28d")).thenReturn(emptyReport());

        mockMvc.perform(get(BASE + "/report").param("window", "28d").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.from").value("2026-09-20"))
                .andExpect(jsonPath("$.window.to").value("2026-10-17"));
        verify(reportService).report(PROJECT_ID, "28d");
    }

    @Test
    void report_withoutAWindow_asksTheServiceForTheDefault() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(reportService.report(eq(PROJECT_ID), any())).thenReturn(emptyReport());

        mockMvc.perform(get(BASE + "/report").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());

        // the spec's default (28d) is applied by the generated interface or, when null, by the service
        org.mockito.ArgumentCaptor<String> window = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reportService).report(eq(PROJECT_ID), window.capture());
        org.assertj.core.api.Assertions.assertThat(window.getValue()).isIn((Object) null, "28d");
    }

    @Test
    void report_withAnUnsupportedWindow_returns400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(reportService.report(PROJECT_ID, "90d"))
                .thenThrow(new BusinessException("Unknown window '90d'; expected [28d, 7d]"));

        mockMvc.perform(get(BASE + "/report").param("window", "90d").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void report_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get(BASE + "/report").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(reportService, never()).report(anyString(), any());
    }

    // ---- benchmark writes ----

    @Test
    void benchmarkPut_byANonMember_isForbiddenAndNothingIsStored() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.empty());

        mockMvc.perform(put(BASE + "/benchmarks/STORE.conversion_rate").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(benchmarkService, never()).upsert(anyString(), anyString(), any(), any());
    }

    @Test
    void benchmarkDelete_byANonMember_isForbiddenAndNothingIsDeleted() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.empty());

        mockMvc.perform(delete(BASE + "/benchmarks/STORE.conversion_rate").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(benchmarkService, never()).delete(anyString(), anyString());
    }

    @Test
    void benchmarkPut_byAReviewer_isForbidden() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(put(BASE + "/benchmarks/STORE.conversion_rate").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(benchmarkService, never()).upsert(anyString(), anyString(), any(), any());
    }

    @Test
    void benchmarkPutAndDelete_keepTheDottedMetricRefIntact() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        MarketingBenchmark saved = new MarketingBenchmark();
        saved.setProjectId(PROJECT_ID);
        saved.setMetricRef("PRODUCT.trial_rate.v2");
        saved.setValue(BigDecimal.valueOf(0.03));
        saved.setSourceName("Example Study");
        saved.setSourceUrl("https://example.com/study");
        saved.setCheckedOn(LocalDate.of(2026, 10, 1));
        saved.setUpdatedAt(OffsetDateTime.parse("2026-10-18T10:00:00Z"));
        when(benchmarkService.upsert(eq(PROJECT_ID), eq("PRODUCT.trial_rate.v2"), any(), any())).thenReturn(saved);

        mockMvc.perform(put(BASE + "/benchmarks/PRODUCT.trial_rate.v2").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricRef").value("PRODUCT.trial_rate.v2"));
        verify(benchmarkService).upsert(eq(PROJECT_ID), eq("PRODUCT.trial_rate.v2"), any(), any());

        mockMvc.perform(delete(BASE + "/benchmarks/PRODUCT.trial_rate.v2").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNoContent());
        verify(benchmarkService).delete(PROJECT_ID, "PRODUCT.trial_rate.v2");
    }
}
