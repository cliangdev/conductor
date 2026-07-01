package com.conductor.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Surfaces the external and internal APIs as separately-selectable Swagger groups, so the split is visible at
 * the API level (Swagger UI shows a group dropdown). Mirrors the source-of-truth split: {@code openapi.yaml} →
 * external, {@code openapi-internal.yaml} → internal, {@code openapi-v2.yaml} → v2 work-items.
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
