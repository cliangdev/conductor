package com.conductor.marketing.links;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.marketing.links.TrackedLinkService.FamilyPerformance;
import com.conductor.marketing.links.TrackedLinkService.LinkInput;
import com.conductor.marketing.links.TrackedLinkService.Page;
import com.conductor.marketing.links.TrackedLinkService.Performance;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.v2.controller.MarketingLinksController;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
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
 * @WebMvcTest slice for {@link MarketingLinksController}: the prefixed paths, membership gating (any member reads,
 * ADMIN or CREATOR writes), status codes, and the response mapping, in particular that a family Apple reported
 * nothing for is sent without a {@code downloads} value (never 0). The numbers themselves are proven in
 * {@link TrackedLinkServiceIntegrationTest}.
 */
@WebMvcTest(MarketingLinksController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class MarketingLinksControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String BASE = "/api/v2/projects/" + PROJECT_ID + "/marketing/links";
    private static final String AUTH = "Bearer valid-token";
    private static final String BODY = """
            {"kind":"BIO","family":"social-bios","token":"ig-bio","label":"Instagram bio"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TrackedLinkService service;
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

    private static TrackedLink link() {
        TrackedLink l = new TrackedLink();
        l.setId("link-1");
        l.setProjectId(PROJECT_ID);
        l.setKind("BIO");
        l.setFamily("social-bios");
        l.setToken("ig-bio");
        l.setUrl("https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio");
        l.setLabel("Instagram bio");
        l.setCreatedAt(OffsetDateTime.parse("2026-11-05T10:00:00Z"));
        return l;
    }

    // ---- reads ----

    @Test
    void listLinks_passesTheFiltersThroughAndMapsTheLinks() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.list(PROJECT_ID, "BIO", "w-1", 50)).thenReturn(new Page(List.of(link()), 3));

        mockMvc.perform(get(BASE).param("kind", "BIO").param("workItemId", "w-1").param("limit", "50")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.links[0].id").value("link-1"))
                .andExpect(jsonPath("$.links[0].kind").value("BIO"))
                .andExpect(jsonPath("$.links[0].family").value("social-bios"))
                .andExpect(jsonPath("$.links[0].token").value("ig-bio"))
                .andExpect(jsonPath("$.links[0].url").value("https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio"))
                .andExpect(jsonPath("$.links[0].label").value("Instagram bio"))
                .andExpect(jsonPath("$.links[0].workItemId").doesNotExist());
    }

    @Test
    void listLinks_withoutFiltersAsksForTheDefaultPage() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.list(eq(PROJECT_ID), isNull(), isNull(), any(Integer.class))).thenReturn(new Page(List.of(), 0));

        mockMvc.perform(get(BASE).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(service).list(eq(PROJECT_ID), isNull(), isNull(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(TrackedLinkService.DEFAULT_LIST_LIMIT);
    }

    @Test
    void listLinks_withAnUnknownKind_returns400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.list(eq(PROJECT_ID), eq("BILLBOARD"), isNull(), any(Integer.class)))
                .thenThrow(new BusinessException("kind must be one of POST, BIO, CREATOR, EMAIL, OTHER"));

        mockMvc.perform(get(BASE).param("kind", "BILLBOARD").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listLinks_byANonMember_isForbidden() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get(BASE).header("Authorization", AUTH)).andExpect(status().isForbidden());
        verify(service, never()).list(anyString(), any(), any(), any(Integer.class));
    }

    @Test
    void performance_sendsNoDownloadsForAFamilyBelowTheThresholdAndNeverZero() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.performance(PROJECT_ID, null, null)).thenReturn(new Performance(
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), List.of(
                new FamilyPerformance("posts", "POST", 12, 31L, false, null, 4800L),
                new FamilyPerformance("social", "BIO", 2, null, true, "below Apple's reporting threshold", null))));

        mockMvc.perform(get(BASE + "/performance").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-29"))
                .andExpect(jsonPath("$.to").value("2026-10-26"))
                .andExpect(jsonPath("$.families[0].family").value("posts"))
                .andExpect(jsonPath("$.families[0].kind").value("POST"))
                .andExpect(jsonPath("$.families[0].tokens").value(12))
                .andExpect(jsonPath("$.families[0].downloads").value(31))
                .andExpect(jsonPath("$.families[0].belowThreshold").value(false))
                .andExpect(jsonPath("$.families[0].note").doesNotExist())
                .andExpect(jsonPath("$.families[0].views").value(4800))
                .andExpect(jsonPath("$.families[1].downloads").doesNotExist())
                .andExpect(jsonPath("$.families[1].belowThreshold").value(true))
                .andExpect(jsonPath("$.families[1].note").value("below Apple's reporting threshold"))
                .andExpect(jsonPath("$.families[1].views").doesNotExist());
    }

    @Test
    void performance_passesAnExplicitWindowThrough() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.performance(PROJECT_ID, LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26)))
                .thenReturn(new Performance(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 26), List.of()));

        mockMvc.perform(get(BASE + "/performance").param("from", "2026-09-29").param("to", "2026-10-26")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.families").isEmpty());
    }

    @Test
    void performance_withABackwardsWindow_returns400() throws Exception {
        memberWithRole(MemberRole.REVIEWER);
        when(service.performance(eq(PROJECT_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new BusinessException("from must be on or before to"));

        mockMvc.perform(get(BASE + "/performance").param("from", "2026-10-26").param("to", "2026-09-29")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    // ---- writes ----

    @Test
    void createLink_byAReviewer_isForbiddenAndNothingIsStored() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", AUTH))
                .andExpect(status().isForbidden());
        verify(service, never()).create(anyString(), any(), any());
    }

    @Test
    void createLink_byAnEditor_returns201WithTheLink() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(service.create(eq(PROJECT_ID), any(LinkInput.class), eq("user-1"))).thenReturn(link());

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("link-1"))
                .andExpect(jsonPath("$.url").value("https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio"))
                .andExpect(jsonPath("$.createdAt").exists());

        ArgumentCaptor<LinkInput> input = ArgumentCaptor.forClass(LinkInput.class);
        verify(service).create(eq(PROJECT_ID), input.capture(), eq("user-1"));
        assertThat(input.getValue()).isEqualTo(new LinkInput("BIO", "social-bios", "ig-bio", "Instagram bio", null));
    }

    @Test
    void createLink_whenTheProjectHasNoProviderToken_returns409WithTheServicesMessage() throws Exception {
        memberWithRole(MemberRole.ADMIN);
        when(service.create(eq(PROJECT_ID), any(LinkInput.class), any()))
                .thenThrow(new ConflictException("Add the App Store Connect provider token"));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY).header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Add the App Store Connect provider token"));
    }

    @Test
    void createLink_withABadToken_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(service.create(eq(PROJECT_ID), any(LinkInput.class), any()))
                .thenThrow(new BusinessException("token must be lowercase letters, digits and dashes"));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BIO\",\"family\":\"f\",\"token\":\"Bad Token\"}")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLink_withAnUnknownKind_returns400() throws Exception {
        memberWithRole(MemberRole.CREATOR);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BILLBOARD\",\"family\":\"f\",\"token\":\"t\"}")
                        .header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(anyString(), any(), any());
    }
}
