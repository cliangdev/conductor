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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
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

        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String keyHash = sha256(token);

        Optional<ProjectApiKey> projectApiKeyOpt = projectApiKeyRepository.findByKeyHash(keyHash);
        if (projectApiKeyOpt.isPresent()) {
            if (projectApiKeyOpt.get().isRevoked()) {
                log.warn("Rejected revoked project API key suffix=...{} on {} {}",
                        token.substring(Math.max(0, token.length() - 4)),
                        request.getMethod(), request.getRequestURI());
            } else {
                ProjectApiKey apiKey = projectApiKeyOpt.get();
                apiKey.setLastUsedAt(OffsetDateTime.now());
                projectApiKeyRepository.save(apiKey);
                log.debug("Authenticated via project API key suffix=...{} project={}",
                        token.substring(Math.max(0, token.length() - 4)), apiKey.getProject().getId());
                ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(apiKey.getProject().getId());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                filterChain.doFilter(request, response);
                return;
            }
        }

        Optional<UserApiKey> userApiKeyOpt = userApiKeyRepository.findByKeyHash(keyHash);
        if (userApiKeyOpt.isPresent()) {
            if (userApiKeyOpt.get().isRevoked()) {
                log.warn("Rejected revoked user API key suffix=...{} on {} {}",
                        token.substring(Math.max(0, token.length() - 4)),
                        request.getMethod(), request.getRequestURI());
            } else {
                UserApiKey userApiKey = userApiKeyOpt.get();
                userApiKey.setLastUsedAt(OffsetDateTime.now());
                userApiKeyRepository.save(userApiKey);
                log.debug("Authenticated via user API key suffix=...{} user={}",
                        token.substring(Math.max(0, token.length() - 4)), userApiKey.getUser().getEmail());
                UserApiKeyAuthenticationToken authentication = new UserApiKeyAuthenticationToken(userApiKey.getUser());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } else if (!projectApiKeyOpt.isPresent()) {
            log.warn("Unknown API key suffix=...{} on {} {}",
                    token.substring(Math.max(0, token.length() - 4)),
                    request.getMethod(), request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
