package com.conductor.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Surfaces the external and internal APIs as separately-selectable Swagger groups, so the split is visible at
 * the API level (Swagger UI shows a group dropdown). Mirrors the source-of-truth split: {@code openapi.yaml} →
 * external, {@code openapi-internal.yaml} → internal. The deprecated v1 {@code issues} surface is isolated in
 * the {@code com.conductor.legacy} package and carved out into its own {@code legacy} group so the canonical
 * {@code external} group stays clean — removing it (#240) is then a delete-package op.
 */
@Configuration
public class OpenApiGroupsConfig {

    static final String LEGACY_PKG = "com.conductor.legacy";

    @Bean
    public GroupedOpenApi externalApi() {
        return GroupedOpenApi.builder()
                .group("external")
                .pathsToMatch(ApiPathConfig.EXTERNAL_BASE + "/**")
                .packagesToExclude(LEGACY_PKG)
                .build();
    }

    @Bean
    public GroupedOpenApi legacyApi() {
        return GroupedOpenApi.builder()
                .group("legacy")
                .packagesToScan(LEGACY_PKG)
                .build();
    }

    @Bean
    public GroupedOpenApi externalV2Api() {
        return GroupedOpenApi.builder()
                .group("v2")
                .pathsToMatch(ApiPathConfig.EXTERNAL_V2_BASE + "/**")
                .build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal")
                .pathsToMatch(ApiPathConfig.INTERNAL_BASE + "/**")
                .build();
    }
}
