package com.conductor.security;

import com.conductor.workflow.RunTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunMcpTokenAuthenticationFilterTest {

    @Mock
    private RunTokenService runTokenService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private RunMcpTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new RunMcpTokenAuthenticationFilter(runTokenService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validMcpTokenSetsWorkflowRunAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer eyJvalid.mcp.token");
        when(runTokenService.parseMcpToken("eyJvalid.mcp.token"))
                .thenReturn(Optional.of(new RunTokenService.McpTokenClaims("proj-1", "run-1")));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isInstanceOf(WorkflowRunAuthenticationToken.class);
        WorkflowRunAuthenticationToken runAuth = (WorkflowRunAuthenticationToken) auth;
        assertThat(runAuth.getProjectId()).isEqualTo("proj-1");
        assertThat(runAuth.getRunId()).isEqualTo("run-1");
    }

    @Test
    void userStyleJwtWithoutMcpTypeLeavesNoAuthentication() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer eyJuser.jwt.token");
        when(runTokenService.parseMcpToken("eyJuser.jwt.token")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noAuthorizationHeaderContinuesChainWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(runTokenService, never()).parseMcpToken(org.mockito.ArgumentMatchers.any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doesNotOverwriteExistingAuthentication() throws Exception {
        UsernamePasswordAuthenticationToken existing =
                new UsernamePasswordAuthenticationToken("existing-user", null);
        SecurityContextHolder.getContext().setAuthentication(existing);
        when(request.getHeader("Authorization")).thenReturn("Bearer eyJvalid.mcp.token");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(runTokenService, never()).parseMcpToken(org.mockito.ArgumentMatchers.any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    }
}
