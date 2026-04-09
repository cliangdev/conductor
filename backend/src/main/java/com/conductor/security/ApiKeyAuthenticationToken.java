package com.conductor.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String projectId;

    public ApiKeyAuthenticationToken(String projectId) {
        super(List.of());
        this.projectId = projectId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return projectId;
    }
}
