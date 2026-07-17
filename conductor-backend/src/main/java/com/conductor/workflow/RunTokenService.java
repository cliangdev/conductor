package com.conductor.workflow;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

@Service
public class RunTokenService {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String TOKEN_TYPE_VALUE = "run-callback";
    private static final String MCP_TOKEN_TYPE_VALUE = "mcp";
    private static final String PROJECT_ID_CLAIM = "projectId";

    private final SecretKey signingKey;

    public RunTokenService(@Value("${jwt.secret}") String jwtSecret) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates a short-lived JWT for a specific run's callback endpoints.
     * Claims: sub=runId, type="run-callback", iat=now, exp=now+ttlHours
     */
    public String generateRunToken(String runId, int ttlHours) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder()
                .subject(runId)
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + ttlHours * 3600_000L))
                .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_VALUE)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates that the token is signed correctly, not expired, and belongs to the expected run.
     */
    public boolean validateRunToken(String token, String expectedRunId) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenType = claims.get(TOKEN_TYPE_CLAIM, String.class);
            if (!TOKEN_TYPE_VALUE.equals(tokenType)) return false;

            return expectedRunId.equals(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Claims carried by a run-scoped MCP token: the project the run belongs to, and the run itself.
     */
    public record McpTokenClaims(String projectId, String runId) {}

    /**
     * Generates a short-lived JWT scoped to a single run's Conductor MCP server callbacks.
     * Claims: sub=runId, projectId, type="mcp", iat=now, exp=now+ttlHours
     */
    public String generateMcpToken(String runId, String projectId, int ttlHours) {
        long nowMs = System.currentTimeMillis();
        return Jwts.builder()
                .subject(runId)
                .issuedAt(new Date(nowMs))
                .expiration(new Date(nowMs + ttlHours * 3600_000L))
                .claim(TOKEN_TYPE_CLAIM, MCP_TOKEN_TYPE_VALUE)
                .claim(PROJECT_ID_CLAIM, projectId)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates and parses an MCP token, returning its claims. Empty if the token is malformed,
     * expired, signed with a different key, not of type "mcp", or missing its projectId claim.
     */
    public Optional<McpTokenClaims> parseMcpToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenType = claims.get(TOKEN_TYPE_CLAIM, String.class);
            if (!MCP_TOKEN_TYPE_VALUE.equals(tokenType)) return Optional.empty();

            String projectId = claims.get(PROJECT_ID_CLAIM, String.class);
            if (projectId == null) return Optional.empty();

            return Optional.of(new McpTokenClaims(projectId, claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
