package com.conductor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness plus a cheap database check. It deliberately does not touch object storage: the deploy smoke test
 * and uptime probes hit this on every deploy and poll, and a bucket listing there cost a remote call per hit.
 */
@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("database", databaseHealthy() ? "ok" : "error");
        return ResponseEntity.ok(response);
    }

    /** JDBC {@code isValid} opens no transaction and runs no SQL statement of its own. */
    private boolean databaseHealthy() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
