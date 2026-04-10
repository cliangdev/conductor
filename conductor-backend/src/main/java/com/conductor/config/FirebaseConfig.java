package com.conductor.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${firebase.service-account-key:}")
    private String serviceAccountKey;

    @Bean
    public FirebaseAuth firebaseAuth() throws IOException {
        if (serviceAccountKey == null || serviceAccountKey.isBlank()) {
            log.warn("FIREBASE_SERVICE_ACCOUNT_KEY not set — Firebase Auth disabled");
            return null;
        }

        if (FirebaseApp.getApps().isEmpty()) {
            InputStream credentialsStream = toInputStream(serviceAccountKey);
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentialsStream))
                    .build();
            FirebaseApp.initializeApp(options);
        }

        return FirebaseAuth.getInstance();
    }

    private InputStream toInputStream(String key) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException e) {
            bytes = key.getBytes();
        }
        return new ByteArrayInputStream(bytes);
    }
}
