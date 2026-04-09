package com.conductor.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@Profile("local")
@RequestMapping("/api/v1/local-files")
public class LocalFileController {

    private final Path storagePath;

    public LocalFileController(
            @Value("${local.storage.path:./local-uploads}") String storagePath) {
        this.storagePath = Paths.get(storagePath).toAbsolutePath();
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> serveFile(HttpServletRequest request) throws IOException {
        String requestUri = request.getRequestURI();
        String prefix = "/api/v1/local-files/";
        if (!requestUri.startsWith(prefix)) {
            return ResponseEntity.notFound().build();
        }
        String filePath = requestUri.substring(prefix.length());
        Path target = storagePath.resolve(filePath).normalize();

        if (!target.startsWith(storagePath)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (!Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }

        byte[] bytes = Files.readAllBytes(target);
        String contentType = Files.probeContentType(target);
        if (contentType == null) contentType = "application/octet-stream";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}
