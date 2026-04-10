package com.conductor.security;

import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.UserApiKey;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
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
        if (projectApiKeyOpt.isPresent() && !projectApiKeyOpt.get().isRevoked()) {
            ProjectApiKey apiKey = projectApiKeyOpt.get();
            apiKey.setLastUsedAt(OffsetDateTime.now());
            projectApiKeyRepository.save(apiKey);

            ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken(apiKey.getProject().getId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
            return;
        }

        Optional<UserApiKey> userApiKeyOpt = userApiKeyRepository.findByKeyHash(keyHash);
        if (userApiKeyOpt.isPresent() && !userApiKeyOpt.get().isRevoked()) {
            UserApiKey userApiKey = userApiKeyOpt.get();
            userApiKey.setLastUsedAt(OffsetDateTime.now());
            userApiKeyRepository.save(userApiKey);

            UserApiKeyAuthenticationToken authentication = new UserApiKeyAuthenticationToken(userApiKey.getUser());
            SecurityContextHolder.getContext().setAuthentication(authentication);
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
