package com.conductor.service;

import com.conductor.entity.User;
import com.conductor.generated.model.AuthResponse;
import com.conductor.generated.model.UserSummary;
import com.conductor.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!local")
public class AuthService {

    private final FirebaseTokenVerifier firebaseTokenVerifier;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public AuthService(
            FirebaseTokenVerifier firebaseTokenVerifier,
            JwtService jwtService,
            UserRepository userRepository) {
        this.firebaseTokenVerifier = firebaseTokenVerifier;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Transactional
    public AuthResponse authenticateWithFirebase(String idToken) throws FirebaseAuthException {
        FirebaseToken firebaseToken = firebaseTokenVerifier.verifyToken(idToken);

        User user = userRepository.findByFirebaseUid(firebaseToken.getUid())
                .orElseGet(() -> createUser(firebaseToken));

        syncProfile(user, firebaseToken);
        userRepository.save(user);

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
