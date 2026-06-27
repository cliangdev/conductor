package com.conductor.support;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Base for repository/service integration tests that need the full application
 * context and a real Postgres but no web server, backed by the shared singleton
 * Postgres from {@link AbstractPostgresIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractNoneWebIntegrationTest extends AbstractPostgresIntegrationTest {
}
