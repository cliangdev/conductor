package com.conductor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalStorageService localStorageService;

    @BeforeEach
    void setUp() {
        localStorageService = new LocalStorageService(tempDir.toString(), "http://localhost:8080");
    }

    @Test
    void uploadWritesFileToCorrectPath() throws IOException {
        byte[] content = "hello world".getBytes();
        localStorageService.upload("proj/issues/issue-1/doc-1/spec.md", content, "text/markdown");

        Path written = tempDir.resolve("proj/issues/issue-1/doc-1/spec.md");
        assertThat(written).exists();
        assertThat(Files.readAllBytes(written)).isEqualTo(content);
    }

    @Test
    void deleteSucceedsSilentlyWhenFileDoesNotExist() {
        localStorageService.delete("nonexistent/file.md");
    }

    @Test
    void isHealthyReturnsFalseWhenStoragePathNotWritable() {
        LocalStorageService service = new LocalStorageService(
                "/nonexistent-root/impossible-path/uploads",
                "http://localhost:8080");
        assertThat(service.isHealthy()).isFalse();
    }

    @Test
    void generateSignedUrlContainsLocalFilesPrefix() {
        String url = localStorageService.generateSignedUrl("proj/issues/doc.md", 15);
        assertThat(url).contains("/api/v1/local-files/");
        assertThat(url).startsWith("http://localhost:8080");
    }
}
