package com.conductor.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for full-stack HTTP E2E tests: boots the app on a random port with a
 * {@link TestRestTemplate}, backed by the shared singleton Postgres from
 * {@link AbstractPostgresIntegrationTest}. Subclasses that add no extra
 * context-differentiating configuration share a single cached Spring context.
 *
 * <p>Do not subclass this for tests that enqueue workflow jobs — see the
 * isolation contract on {@link AbstractPostgresIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractE2ETest extends AbstractPostgresIntegrationTest {

    /**
     * Point local file storage at an ABSOLUTE temp dir, shared by all E2E tests
     * (files are keyed by UUID gcs paths, so they never collide). This must be
     * absolute and registered here via @DynamicPropertySource: the `local`
     * profile's application-local.properties sets a relative `./local-uploads`,
     * and a relative path breaks LocalFileController's path-traversal guard
     * (`toAbsolutePath()` keeps the `./`, but the request path is normalized, so
     * `startsWith` fails → 403). A single constant value keeps the context shared.
     */
    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("local.storage.path",
                () -> java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "conductor-test-storage")
                        .toString());
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
