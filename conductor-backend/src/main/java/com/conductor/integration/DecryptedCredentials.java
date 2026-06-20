package com.conductor.integration;

import java.time.Instant;
import java.util.Map;

public record DecryptedCredentials(String accessToken, String refreshToken, Instant expiresAt, Map<String, Object> configJson) {}
