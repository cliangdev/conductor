package com.conductor.marketing.verification;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.verification.VerificationLedgerService.BackendFigure;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.marketing.verification.VerificationLedgerService.PlatformReadFigure;
import com.conductor.marketing.verification.VerificationLedgerService.ReadEntry;
import com.conductor.marketing.verification.VerificationLedgerService.RecordedRow;
import com.conductor.marketing.verification.VerificationLedgerService.Summary;
import com.conductor.marketing.verification.VerificationLedgerService.Verdict;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.v2.controller.MarketingVerificationController;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link MarketingVerificationController}: the window reaching the service, membership gating
 * (anyone reads, an editor writes), the 201 with the recomputed row, a refused read answering 400, and no way at
 * all to update or delete a read. The verdicts are proven in {@code VerificationLedgerServiceIntegrationTest}.
 */
@WebMvcTest(MarketingVerificationController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingVerificationControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v2/projects/" + PROJECT_ID + "/marketing/verification";
    private static final String BODY = """
            {"channel":"ADS","metric":"installs","from":"2026-09-29","to":"2026-10-26","value":23,
             "sourceName":"Apple Ads console","note":"weekly read"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private VerificationLedgerService ledgerService;
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

    private static LedgerRow row() {
        PlatformReadFigure read = new PlatformReadFigure("read-1", 23, LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 10, 26), "Apple Ads console", "weekly read", "user-1",
                OffsetDateTime.parse("2026-10-29T12:00:00Z"), 7.0, false);
        BackendFigure backend = new BackendFigure(23, 7, 7, "Rexipe", LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 10, 5), false);
        return new LedgerRow("ADS", "installs", "ADS.installs", "Installs", MetricCatalog.Unit.COUNT, null,
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), 7.0, backend, read, Verdict.BACKEND, true, false,
                "Trust the product backend.");
    }

    // ---- ledger ----

    @Test
    void ledger_passesTheWindowThroughAndReturnsRowsAndSummary() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(ledgerService.ledger(PROJECT_ID, "2026-09-29..2026-10-26")).thenReturn(new Ledger(
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), List.of(row()),
                new Summary(0, 0, 1, 0, 0, 1, 0)));

        mockMvc.perform(get(BASE).param("window", "2026-09-29..2026-10-26").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.from").value("2026-09-29"))
                .andExpect(jsonPath("$.window.to").value("2026-10-26"))
                .andExpect(jsonPath("$.rows[0].metricRef").value("ADS.installs"))
                .andExpect(jsonPath("$.rows[0].verdict").value("BACKEND"))
                .andExpect(jsonPath("$.rows[0].mismatch").value(true))
                .andExpect(jsonPath("$.rows[0].backend.value").value(23.0))
                .andExpect(jsonPath("$.rows[0].backend.daysCompared").value(7))
                .andExpect(jsonPath("$.rows[0].platformRead.sourceName").value("Apple Ads console"))
                .andExpect(jsonPath("$.rows[0].platformRead.conductorValueOverReadDays").value(7.0))
                .andExpect(jsonPath("$.rows[0].platformRead.matches").value(false))
                .andExpect(jsonPath("$.summary.backend").value(1))
                .andExpect(jsonPath("$.summary.mismatched").value(1));
    }

    @Test
    void ledger_leavesOutWhatIsUnknownInsteadOfSendingZero() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        LedgerRow unchecked = new LedgerRow("SEARCH", "clicks", "SEARCH.clicks", "Clicks", MetricCatalog.Unit.COUNT,
                null, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), null, null, null, Verdict.UNCHECKED,
                false, false, "Only Conductor's connector reports this.");
        when(ledgerService.ledger(eq(PROJECT_ID), any())).thenReturn(new Ledger(LocalDate.of(2026, 9, 29), null,
                List.of(unchecked), new Summary(0, 0, 0, 0, 1, 0, 0)));

        mockMvc.perform(get(BASE).header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window.to").doesNotExist())
                .andExpect(jsonPath("$.rows[0].conductorValue").doesNotExist())
                .andExpect(jsonPath("$.rows[0].backend").doesNotExist())
                .andExpect(jsonPath("$.rows[0].platformRead").doesNotExist())
                .andExpect(jsonPath("$.rows[0].verdict").value("UNCHECKED"));
    }

    @Test
    void ledger_withABadWindow_returns400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(ledgerService.ledger(PROJECT_ID, "yesterday"))
                .thenThrow(new BusinessException("Unknown window 'yesterday'"));

        mockMvc.perform(get(BASE).param("window", "yesterday").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ledger_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get(BASE).header("Authorization", "Bearer valid-token")).andExpect(status().isForbidden());
        verify(ledgerService, never()).ledger(anyString(), any());
    }

    // ---- recording ----

    @Test
    void record_byAnEditor_returns201WithTheRecomputedRowAndTheActorsUserId() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(ledgerService.record(eq(PROJECT_ID), eq("ADS"), eq("installs"), eq(LocalDate.of(2026, 9, 29)),
                eq(LocalDate.of(2026, 10, 26)), eq(23.0), eq("Apple Ads console"), eq("weekly read"), eq("user-1")))
                .thenReturn(new RecordedRow(row().platformRead(), row()));

        mockMvc.perform(post(BASE + "/reads").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metricRef").value("ADS.installs"))
                .andExpect(jsonPath("$.verdict").value("BACKEND"))
                .andExpect(jsonPath("$.platformRead.id").value("read-1"));
    }

    @Test
    void record_byAReviewer_isForbiddenAndNothingIsStored() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(post(BASE + "/reads").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(ledgerService, never()).record(anyString(), anyString(), anyString(), any(), any(), anyDouble(), any(),
                any(), any());
    }

    @Test
    void record_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(Optional.empty());

        mockMvc.perform(post(BASE + "/reads").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void record_withoutASourceName_returns400BeforeTheServiceIsCalled() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(post(BASE + "/reads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"ADS\",\"metric\":\"installs\",\"from\":\"2026-09-29\","
                                + "\"to\":\"2026-10-26\",\"value\":23}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
        verify(ledgerService, never()).record(anyString(), anyString(), anyString(), any(), any(), anyDouble(), any(),
                any(), any());
    }

    @Test
    void record_aNegativeValue_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(post(BASE + "/reads").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"value\":23", "\"value\":-1"))
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void record_aReadTheServiceRefuses_returns400WithItsMessage() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(ledgerService.record(anyString(), anyString(), anyString(), any(), any(), anyDouble(), any(), any(), any()))
                .thenThrow(new BusinessException("A read must end yesterday (2026-10-28) or earlier"));

        mockMvc.perform(post(BASE + "/reads").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A read must end yesterday (2026-10-28) or earlier"));
    }

    // ---- retracting ----

    private static final String RETRACT = BASE + "/reads/read-1/retract";

    @Test
    void retract_byAnEditor_returns200WithTheRetractionFieldsAndTheActorsUserId() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(ledgerService.retract(PROJECT_ID, "read-1", "RevenueCat is retired", "user-1")).thenReturn(new ReadEntry(
                "read-1", "PRODUCT", "trial_started", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), 0,
                "RevenueCat", null, "user-0", OffsetDateTime.parse("2026-10-29T12:00:00Z"), 7.0, null, "PLATFORM",
                OffsetDateTime.parse("2026-11-08T10:00:00Z"), "user-1", "RevenueCat is retired"));

        mockMvc.perform(post(RETRACT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"RevenueCat is retired\"}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("read-1"))
                .andExpect(jsonPath("$.retractedBy").value("user-1"))
                .andExpect(jsonPath("$.retractionReason").value("RevenueCat is retired"))
                .andExpect(jsonPath("$.retractedAt").exists());
    }

    @Test
    void retract_byAReviewer_isForbiddenAndNothingIsRetracted() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(post(RETRACT).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong\"}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
        verify(ledgerService, never()).retract(anyString(), anyString(), any(), any());
    }

    @Test
    void retract_withoutAReason_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(ledgerService.retract(anyString(), anyString(), any(), any()))
                .thenThrow(new BusinessException("reason is required: say why the read is wrong"));

        mockMvc.perform(post(RETRACT).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(RETRACT).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retract_aReadOfAnotherProject_returns404() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(ledgerService.retract(anyString(), anyString(), any(), any()))
                .thenThrow(new jakarta.persistence.EntityNotFoundException("Platform read not found"));

        mockMvc.perform(post(RETRACT).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"wrong\"}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound());
    }

    // ---- history ----

    @Test
    void history_listsTheReadsAndPassesTheFiltersThrough() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(ledgerService.reads(PROJECT_ID, "ads", "installs", 20)).thenReturn(List.of(
                new ReadEntry("read-2", "ADS", "installs", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), 23,
                        "Apple Ads console", null, null, OffsetDateTime.parse("2026-10-29T12:00:00Z"), 7.0, 23.0,
                        "BACKEND", null, null, null)));

        mockMvc.perform(get(BASE + "/reads").param("channel", "ads").param("metric", "installs").param("limit", "20")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reads[0].id").value("read-2"))
                .andExpect(jsonPath("$.reads[0].verdict").value("BACKEND"))
                .andExpect(jsonPath("$.reads[0].conductorValue").value(7.0))
                .andExpect(jsonPath("$.reads[0].backendValue").value(23.0))
                .andExpect(jsonPath("$.reads[0].note").doesNotExist());
    }

    @Test
    void history_withoutFilters_asksForTheDefaultPage() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(ledgerService.reads(eq(PROJECT_ID), isNull(), isNull(), eq(100))).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/reads").header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reads").isEmpty());
    }

    // ---- append-only ----

    @Test
    void thereIsNoEndpointToUpdateOrDeleteARead() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(put(BASE + "/reads/read-1").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(patch(BASE + "/reads/read-1").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete(BASE + "/reads/read-1").header("Authorization", "Bearer valid-token"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(delete(BASE + "/reads").header("Authorization", "Bearer valid-token"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(put(BASE + "/reads").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().is4xxClientError());
        verifyNoMoreInteractions(ledgerService);
    }
}
