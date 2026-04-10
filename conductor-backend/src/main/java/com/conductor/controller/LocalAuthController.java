package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.model.AuthResponse;
import com.conductor.generated.model.UserSummary;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Profile("local")
@RequestMapping("/api/v1/auth")
public class LocalAuthController {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final String devPassword;

    public LocalAuthController(
            UserRepository userRepository,
            JwtService jwtService,
            @Value("${local.dev.password:conductor}") String devPassword) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.devPassword = devPassword;
    }

    @PostMapping("/local")
    public ResponseEntity<AuthResponse> localAuth(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String password = body.get("password");

        if (email == null || email.isBlank() || !devPassword.equals(password)) {
            return ResponseEntity.status(401).build();
        }

        String firebaseUid = "local:" + email;
        User user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseGet(() -> {
                    User newUser = new User();
                    newUser.setFirebaseUid(firebaseUid);
                    newUser.setEmail(email);
                    newUser.setName(email);
                    return userRepository.save(newUser);
                });

        String accessToken = jwtService.generateToken(user.getId());

        UserSummary userSummary = new UserSummary(user.getId(), user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl());

        return ResponseEntity.ok(new AuthResponse(accessToken, userSummary));
    }
}
