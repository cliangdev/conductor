package com.conductor.security;

import com.conductor.entity.User;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.cache.Cache;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    /** Authenticated users by id (60s TTL, bounded); null disables caching. */
    private final Cache userCache;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this(jwtService, userRepository, null);
    }

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository, Cache userCache) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.userCache = userCache;
    }

    /**
     * The user for a token's id. A hit costs nothing; a miss is a pooled-connection checkout plus a round
     * trip to a remote database, which every authenticated request used to pay. The cached entity is only
     * ever read (it is detached either way, since open-in-view is off), and a missing user is not cached.
     */
    private Optional<User> loadUser(String userId) {
        if (userCache == null) {
            return userRepository.findById(userId);
        }
        User cached = userCache.get(userId, User.class);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<User> loaded = userRepository.findById(userId);
        loaded.ifPresent(u -> userCache.put(userId, u));
        return loaded;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // An upstream filter (run-MCP token, API key) may have already authenticated this request —
        // don't re-parse the bearer token as a user JWT (all token types share jwt.secret and are
        // disambiguated by claims, so re-parsing is wasted work at best and fragile at worst).
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);

        if (token != null && jwtService.validateToken(token)) {
            String userId = jwtService.getUserIdFromToken(token);
            Optional<User> userOpt = loadUser(userId);

            if (userOpt.isPresent()) {
                User user = userOpt.get();
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, null, List.of());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerHeader = request.getHeader("Authorization");
        if (bearerHeader != null && bearerHeader.startsWith("Bearer ")) {
            return bearerHeader.substring(7);
        }

        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("access_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }
}
