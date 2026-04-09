package com.conductor.controller;

import com.conductor.service.GcpStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final GcpStorageService gcpStorageService;

    public HealthController(GcpStorageService gcpStorageService) {
        this.gcpStorageService = gcpStorageService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "ok");
        if (gcpStorageService.isHealthy()) {
            response.put("storage", "ok");
        } else {
            response.put("storage", "error");
        }
        return ResponseEntity.ok(response);
    }
}
