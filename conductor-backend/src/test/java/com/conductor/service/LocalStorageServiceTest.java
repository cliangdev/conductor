package com.conductor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void uploadRejectsPathEscapingStorageRoot() {
        assertThatThrownBy(() -> localStorageService.upload("../evil.txt", "pwned".getBytes(), "text/plain"))
                .isInstanceOf(SecurityException.class);

        assertThat(tempDir.getParent().resolve("evil.txt")).doesNotExist();
    }

    @Test
    void downloadRejectsPathEscapingStorageRoot() throws IOException {
        // A file that genuinely exists just outside the storage root — proves the guard blocks
        // reading it via a crafted gcsPath, not merely that the target happens to be missing.
        Path outsideFile = tempDir.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        Files.write(outsideFile, "secret".getBytes());
        try {
            String traversal = "../" + outsideFile.getFileName();

            assertThatThrownBy(() -> localStorageService.download(traversal))
                    .isInstanceOf(SecurityException.class);
        } finally {
            Files.deleteIfExists(outsideFile);
        }
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
    /**
     * Regression: the default {@code local.storage.path} is the RELATIVE {@code ./local-uploads}, whose
     * absolute form keeps a literal "." segment. {@code resolveWithinStorageRoot} compares a *normalized*
     * target against the root, so an un-normalized root made every legitimate path fail the
     * {@code startsWith} check — the default configuration could not store a single file, and every caller
     * (assets, documents, workflow artifacts) got a 500 from a path-traversal guard firing on a path that
     * traverses nothing.
     */
    @Test
    void storesAFileWhenTheConfiguredRootIsRelative(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("uploads");
        Files.createDirectories(base);
        // The shape that broke it: a root reached through a "." segment, exactly like "./local-uploads".
        String rootWithDotSegment = tmp + java.io.File.separator + "." + java.io.File.separator + "uploads";
        LocalStorageService service = new LocalStorageService(rootWithDotSegment, "http://localhost:8080");

        service.upload("marketing-assets/proj/item/asset-teaser.png", new byte[] {1, 2, 3}, "image/png");

        assertThat(base.resolve("marketing-assets/proj/item/asset-teaser.png")).exists();
        assertThat(service.download("marketing-assets/proj/item/asset-teaser.png")).containsExactly(1, 2, 3);
    }

    /** The guard still has to bite on a path that genuinely escapes. */
    @Test
    void stillRefusesAPathThatEscapesTheRoot(@TempDir Path tmp) {
        LocalStorageService service =
                new LocalStorageService(tmp.resolve("uploads").toString(), "http://localhost:8080");

        assertThatThrownBy(() -> service.upload("../escaped.txt", new byte[] {1}, "text/plain"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.upload("a/../../escaped.txt", new byte[] {1}, "text/plain"))
                .isInstanceOf(SecurityException.class);
    }

}
