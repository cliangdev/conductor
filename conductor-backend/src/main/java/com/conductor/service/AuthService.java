package com.conductor.service;

import com.conductor.entity.User;
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
    private final OrgService orgService;

    public AuthService(
            FirebaseTokenVerifier firebaseTokenVerifier,
            JwtService jwtService,
            UserRepository userRepository,
            TransactionTemplate transactionTemplate,
            OrgService orgService) {
        this.firebaseTokenVerifier = firebaseTokenVerifier;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.transactionTemplate = transactionTemplate;
        this.orgService = orgService;
    }

    public AuthResponse authenticateWithFirebase(String idToken) throws FirebaseAuthException {
        FirebaseToken firebaseToken = firebaseTokenVerifier.verifyToken(idToken);

        User user = transactionTemplate.execute(status -> {
            User u = userRepository.findByFirebaseUid(firebaseToken.getUid())
                    .orElseGet(() -> createUser(firebaseToken));
            syncProfile(u, firebaseToken);
            return userRepository.save(u);
        });

        try {
            orgService.getOrCreatePersonalOrg(user.getId(), user.getDisplayName(), user.getEmail());
        } catch (Exception e) {
            log.error("Failed to auto-create personal org for user {}: {}", user.getId(), e.getMessage(), e);
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
        user.setName(firebaseToken.getName());
        String picture = (String) firebaseToken.getClaims().get("picture");
        user.setAvatarUrl(picture);
    }
}
