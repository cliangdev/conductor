package com.conductor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Single source of truth for the API base paths. Rather than each controller hand-declaring its
 * prefix (which is fragile — a forgotten {@code @RequestMapping("/api/v1")} silently registers a
 * controller at bare paths), the prefix is applied structurally here, scoped by controller package:
 *
 * <ul>
 *   <li><b>External API</b> ({@code /api/v1}) — the public, versioned contract. Every
 *       {@link RestController} under {@code com.conductor} <i>except</i> the internal package.</li>
 *   <li><b>Internal control-plane</b> ({@code /internal/v1}) — worker/daemon callbacks authenticated
 *       by per-run tokens, not the app JWT. Every {@link RestController} under
 *       {@code com.conductor.internal}.</li>
 *   <li><b>External API v2</b> ({@code /api/v2}) — the next canonical external contract (Work Items).
 *       Every {@link RestController} under {@code com.conductor.v2}. This rule is checked before the
 *       generic external rule so a v2 controller is mapped at {@code /api/v2} only, never also
 *       {@code /api/v1}.</li>
 * </ul>
 *
 * The package a controller lives in is the only thing that decides which space it belongs to, so the
 * two surfaces stay cleanly separated (see also {@code openapi.yaml} vs {@code openapi-internal.yaml}
 * and the two springdoc groups). Controllers therefore map at <i>bare</i> paths; springdoc/actuator
 * are untouched because they are not {@code com.conductor} {@link RestController}s.
 */
@Configuration
public class ApiPathConfig implements WebMvcConfigurer {

    static final String EXTERNAL_BASE = "/api/v1";
    static final String EXTERNAL_V2_BASE = "/api/v2";
    static final String INTERNAL_BASE = "/internal/v1";
    private static final String INTERNAL_PKG = "com.conductor.internal";
    private static final String V2_PKG = "com.conductor.v2";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Order matters: the v2 rule must run before the generic external rule so a v2 controller is
        // mapped at /api/v2 only — the external predicate excludes the v2 package to avoid double-prefixing.
        configurer.addPathPrefix(EXTERNAL_V2_BASE, ApiPathConfig::isExternalV2Controller);
        configurer.addPathPrefix(EXTERNAL_BASE, ApiPathConfig::isExternalController);
        configurer.addPathPrefix(INTERNAL_BASE, ApiPathConfig::isInternalController);
    }

    private static boolean isInternalController(Class<?> clz) {
        return clz.isAnnotationPresent(RestController.class)
                && clz.getPackageName().startsWith(INTERNAL_PKG);
    }

    private static boolean isExternalV2Controller(Class<?> clz) {
        return clz.isAnnotationPresent(RestController.class)
                && clz.getPackageName().startsWith(V2_PKG);
    }

    private static boolean isExternalController(Class<?> clz) {
        return clz.isAnnotationPresent(RestController.class)
                && clz.getPackageName().startsWith("com.conductor")
                && !clz.getPackageName().startsWith(INTERNAL_PKG)
                && !clz.getPackageName().startsWith(V2_PKG);
    }
}
