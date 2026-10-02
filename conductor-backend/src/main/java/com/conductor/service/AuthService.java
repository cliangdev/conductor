package com.conductor.service;

import com.conductor.entity.User;
import com.conductor.exception.EmailNotVerifiedException;
import com.conductor.generated.model.AuthResponse;
import com.conductor.generated.model.UserSummary;
import com.conductor.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!local")
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final FirebaseTokenVerifier firebaseTokenVerifier;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final ProjectService projectService;

    public AuthService(
            FirebaseTokenVerifier firebaseTokenVerifier,
            JwtService jwtService,
            UserRepository userRepository,
            TransactionTemplate transactionTemplate,
            ProjectService projectService) {
        this.firebaseTokenVerifier = firebaseTokenVerifier;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.projectService = projectService;
    }

    public AuthResponse authenticateWithFirebase(String idToken) throws FirebaseAuthException {
        FirebaseToken firebaseToken = firebaseTokenVerifier.verifyToken(idToken);

        // Email/password accounts are untrusted until verified; refuse before any user row or workspace exists.
        if (!firebaseToken.isEmailVerified()) {
            throw new EmailNotVerifiedException("Email address is not verified");
        }

        User user = transactionTemplate.execute(status -> {
            User u = userRepository.findByFirebaseUid(firebaseToken.getUid())
                    .orElseGet(() -> createUser(firebaseToken));
            syncProfile(u, firebaseToken);
            return userRepository.save(u);
        });

        try {
            projectService.ensureDefaultWorkspace(user);
        } catch (Exception e) {
            log.error("Failed to auto-create default workspace for user {}: {}", user.getId(), e.getMessage(), e);
        }

        String accessToken = jwtService.generateToken(user.getId());

        UserSummary userSummary = new UserSummary(user.getId(), user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl());

        return new AuthResponse(accessToken, userSummary);
    }

    private User createUser(FirebaseToken firebaseToken) {
        User user = new User();
        user.setFirebaseUid(firebaseToken.getUid());
        user.setEmail(firebaseToken.getEmail());
        return user;
    }

    private void syncProfile(User user, FirebaseToken firebaseToken) {
        // A missing or blank claim never overwrites a stored value (email/password users have no
        // picture and may have no name claim).
        String name = firebaseToken.getName();
        if (name != null && !name.isBlank()) {
            user.setName(name);
        }
        Object picture = firebaseToken.getClaims().get("picture");
        if (picture instanceof String p && !p.isBlank()) {
            user.setAvatarUrl(p);
        }
    }
}
