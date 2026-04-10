package com.conductor.security;

import com.conductor.entity.User;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

public class UserApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final User user;

    public UserApiKeyAuthenticationToken(User user) {
        super(List.of());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }
}
