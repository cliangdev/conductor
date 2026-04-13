package com.conductor.security;

import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.UserApiKey;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ProjectApiKeyRepository projectApiKeyRepository;
    private final UserApiKeyRepository userApiKeyRepository;

    public ApiKeyAuthenticationFilter(ProjectApiKeyRepository projectApiKeyRepository, UserApiKeyRepository userApiKeyRepository) {
        this.projectApiKeyRepository = projectApiKeyRepository;
        this.userApiKeyRepository = userApiKeyRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        // JWTs always start with eyJ (base64url for '{"') — skip to JwtAuthenticationFilter
        if (token.startsWith("eyJ")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String keySuffix = token.substring(Math.max(0, token.length() - 4));

        Optional<ProjectApiKey> projectApiKeyOpt = projectApiKeyRepository.findByKeyValueWithProject(token);

        if (projectApiKeyOpt.isPresent()) {
            if (projectApiKeyOpt.get().isRevoked()) {
                log.warn("Rejected revoked project API key suffix=...{} on {} {}",
                        keySuffix, request.getMethod(), request.getRequestURI());
            } else {
                ProjectApiKey apiKey = projectApiKeyOpt.get();
                apiKey.setLastUsedAt(OffsetDateTime.now());
                projectApiKeyRepository.save(apiKey);
                log.info("Authenticated via project API key suffix=...{} project={} on {} {}",
                        keySuffix, apiKey.getProject().getId(),
                        request.getMethod(), request.getRequestURI());
                ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(apiKey.getProject().getId());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                filterChain.doFilter(request, response);
                return;
            }
        }

        Optional<UserApiKey> userApiKeyOpt = userApiKeyRepository.findByKeyValueWithUser(token);
        if (userApiKeyOpt.isPresent()) {
            if (userApiKeyOpt.get().isRevoked()) {
                log.warn("Rejected revoked user API key suffix=...{} on {} {}",
                        keySuffix, request.getMethod(), request.getRequestURI());
            } else {
                UserApiKey userApiKey = userApiKeyOpt.get();
                userApiKey.setLastUsedAt(OffsetDateTime.now());
                userApiKeyRepository.save(userApiKey);
                log.info("Authenticated via user API key suffix=...{} user={} on {} {}",
                        keySuffix, userApiKey.getUser().getEmail(),
                        request.getMethod(), request.getRequestURI());
                UserApiKeyAuthenticationToken authentication = new UserApiKeyAuthenticationToken(userApiKey.getUser());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } else if (!projectApiKeyOpt.isPresent()) {
            log.warn("Unknown API key suffix=...{} on {} {}",
                    keySuffix, request.getMethod(), request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

}
