package com.conductor.security;

import com.conductor.entity.ProjectApiKey;
import com.conductor.repository.ProjectApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final ProjectApiKeyRepository projectApiKeyRepository;

    public ApiKeyAuthenticationFilter(ProjectApiKeyRepository projectApiKeyRepository) {
        this.projectApiKeyRepository = projectApiKeyRepository;
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
        Optional<ProjectApiKey> apiKeyOpt = projectApiKeyRepository.findByKeyHash(keyHash);

        if (apiKeyOpt.isEmpty() || apiKeyOpt.get().isRevoked()) {
            filterChain.doFilter(request, response);
            return;
        }

        ProjectApiKey apiKey = apiKeyOpt.get();
        apiKey.setLastUsedAt(OffsetDateTime.now());
        projectApiKeyRepository.save(apiKey);

        ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(apiKey.getProject().getId());
        SecurityContextHolder.getContext().setAuthentication(authentication);

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
