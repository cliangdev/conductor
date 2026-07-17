package com.conductor.security;

import com.conductor.workflow.RunTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates requests bearing a run-scoped MCP token ({@link RunTokenService#generateMcpToken}) —
 * the credential a Claude Code container's Conductor MCP server presents on behalf of a workflow run,
 * in place of a user-created project API key. Tokens are JWTs (start with {@code eyJ}), so this filter
 * must run before {@link ApiKeyAuthenticationFilter} (which skips them) and before
 * {@link JwtAuthenticationFilter} (which would otherwise reject them as a user JWT).
 */
public class RunMcpTokenAuthenticationFilter extends OncePerRequestFilter {

    private final RunTokenService runTokenService;

    public RunMcpTokenAuthenticationFilter(RunTokenService runTokenService) {
        this.runTokenService = runTokenService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (token.startsWith("eyJ") && SecurityContextHolder.getContext().getAuthentication() == null) {
                Optional<RunTokenService.McpTokenClaims> claims = runTokenService.parseMcpToken(token);
                if (claims.isPresent()) {
                    WorkflowRunAuthenticationToken authentication =
                            new WorkflowRunAuthenticationToken(claims.get().projectId(), claims.get().runId());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
