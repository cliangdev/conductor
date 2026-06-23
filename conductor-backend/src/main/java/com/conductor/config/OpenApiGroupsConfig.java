package com.conductor.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Surfaces the external and internal APIs as two separately-selectable Swagger groups, so the split is
 * visible at the API level (Swagger UI shows a group dropdown). Mirrors the source-of-truth split:
 * {@code openapi.yaml} → external, {@code openapi-internal.yaml} → internal.
 */
@Configuration
public class OpenApiGroupsConfig {

    @Bean
    public GroupedOpenApi externalApi() {
        return GroupedOpenApi.builder()
                .group("external")
                .pathsToMatch(ApiPathConfig.EXTERNAL_BASE + "/**")
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
