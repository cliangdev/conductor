package com.conductor.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests that need a real Postgres.
 *
 * <p>Uses the Testcontainers "singleton container" pattern: ONE container is
 * started once per JVM (in a static initializer) and shared by every subclass,
 * so the suite starts a single Postgres instead of one container per test class.
 * The container is intentionally never stopped — Testcontainers' Ryuk reaper
 * removes it when the JVM exits.
 *
 * <p>Because every subclass registers the SAME JDBC URL, their Spring context
 * configuration is identical, so Spring's context cache reuses one context per
 * {@code webEnvironment} instead of building a fresh context (and Hikari pool)
 * for each class.
 *
 * <p><b>Isolation contract:</b> all subclasses share ONE database. Tests must
 * isolate themselves by unique identifiers (UUIDs) and must NOT assume a globally
 * empty database. Tests that <i>enqueue workflow jobs</i> must NOT use this base:
 * the workflow job-queue scheduler claims any ready job, so a shared queue would
 * let one test's scheduler execute another test's job. Those tests declare their
 * own {@code @Container} for a private database (see the workflow E2E tests).
 */
@ActiveProfiles("local")
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
