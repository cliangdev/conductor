package com.conductor.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

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

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
