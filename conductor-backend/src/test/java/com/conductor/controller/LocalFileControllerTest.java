package com.conductor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read side of local file storage. Its path guard mirrors {@code LocalStorageService}'s, and carried
 * the same defect: the root was set with {@code toAbsolutePath()} but not {@code normalize()}, while the
 * guard compares a *normalized* target against it. The default {@code local.storage.path} is the RELATIVE
 * {@code ./local-uploads}, whose absolute form keeps a literal "." segment — so every legitimate read was
 * refused with 403, including every image preview on a Post.
 */
class LocalFileControllerTest {

    private static HttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/local-files/" + path);
        request.setRequestURI("/api/v1/local-files/" + path);
        return request;
    }

    @Test
    void servesAFileWhenTheConfiguredRootIsRelative(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("uploads");
        Files.createDirectories(base.resolve("marketing-assets/proj/item"));
        Files.write(base.resolve("marketing-assets/proj/item/a-teaser.jpg"), new byte[] {1, 2, 3});

        // The shape that broke it: a root reached through a "." segment, exactly like "./local-uploads".
        String rootWithDotSegment = tmp + File.separator + "." + File.separator + "uploads";
        LocalFileController controller = new LocalFileController(rootWithDotSegment);

        ResponseEntity<byte[]> response =
                controller.serveFile(get("marketing-assets/proj/item/a-teaser.jpg"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void stillRefusesAPathThatEscapesTheRoot(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("uploads"));
        Files.write(tmp.resolve("secret.txt"), new byte[] {9});
        LocalFileController controller = new LocalFileController(tmp.resolve("uploads").toString());

        assertThat(controller.serveFile(get("../secret.txt")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.serveFile(get("a/../../secret.txt")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void returnsNotFoundForAMissingFileWithinTheRoot(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("uploads"));
        LocalFileController controller = new LocalFileController(tmp.resolve("uploads").toString());

        assertThat(controller.serveFile(get("nothing/here.png")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
