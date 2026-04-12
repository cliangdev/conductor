package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.AuthApi;
import com.conductor.generated.model.AuthResponse;
import com.conductor.generated.model.CliCallbackResponse;
import com.conductor.generated.model.FirebaseLoginRequest;
import com.conductor.generated.model.UserProfile;
import com.conductor.service.AuthService;
import com.conductor.service.CliLoginService;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!local")
@RequestMapping("/api/v1")
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final CliLoginService cliLoginService;
    private final HttpServletResponse httpServletResponse;

    public AuthController(
            AuthService authService,
            CliLoginService cliLoginService,
            HttpServletResponse httpServletResponse) {
        this.authService = authService;
        this.cliLoginService = cliLoginService;
        this.httpServletResponse = httpServletResponse;
    }

    @Override
    public ResponseEntity<AuthResponse> firebaseLogin(FirebaseLoginRequest firebaseLoginRequest) {
        try {
            AuthResponse response = authService.authenticateWithFirebase(firebaseLoginRequest.getIdToken());
            return ResponseEntity.ok(response);
        } catch (FirebaseAuthException e) {
            throw new com.conductor.exception.FirebaseAuthenticationException(e.getMessage(), e);
        }
    }

    @Override
    public ResponseEntity<UserProfile> getCurrentUser() {
        User user = currentUser();
        UserProfile profile = new UserProfile(user.getId(), user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .displayName(user.getDisplayName());
        return ResponseEntity.ok(profile);
    }

    @Override
    public ResponseEntity<Void> logout() {
        Cookie cookie = new Cookie("access_token", "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        httpServletResponse.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CliCallbackResponse> cliCallback(Integer port, String projectId) {
        User caller = currentUser();
        CliCallbackResponse response = cliLoginService.generateCredentials(port, projectId, caller);
        return ResponseEntity.ok(response);
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
