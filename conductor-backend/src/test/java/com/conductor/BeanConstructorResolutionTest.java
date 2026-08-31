package com.conductor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the deploy-only bean-wiring failure class: a Spring-managed class with MULTIPLE
 * constructors and no {@code @Autowired} makes Spring fall back to a (usually nonexistent) no-arg
 * constructor, failing context refresh. {@code @Profile("!local")} beans are never instantiated by
 * the test suite, so without this scan the failure only surfaces when a real deploy boots the
 * container (it took down the 2026-07-12 preview deploy via {@code DiscordActionConnector}'s
 * package-private test constructor).
 *
 * <p>Scans TWICE and unions the results, which is not incidental. {@code
 * ClassPathScanningCandidateComponentProvider} evaluates {@code @Profile} against its own
 * {@link org.springframework.core.env.Environment}, so a single scan sees only half the beans: with no
 * active profile it finds every {@code @Profile("!local")} bean and silently skips every
 * {@code @Profile("local")} one, and with {@code local} active it does the exact reverse. This file
 * previously claimed to cover "every profile's beans uniformly" while doing one default-environment scan,
 * so local-profile beans went unchecked. Verified: a default-environment scan finds {@code GscConnector}
 * but not {@code LocalGscConnector}.
 */
class BeanConstructorResolutionTest {

    @Test
    void everyComponentHasAnUnambiguousConstructor() {
        Set<String> scanned = new LinkedHashSet<>();
        scanned.addAll(scanFor(null));      // every @Profile("!local") bean
        scanned.addAll(scanFor("local"));   // every @Profile("local") bean

        // Non-vacuity: a scan that silently found nothing would let this test pass while checking nothing,
        // which is the failure mode the two-scan union exists to prevent. Assert both halves are present.
        assertTrue(scanned.contains("com.conductor.integration.connector.gsc.GscConnector"),
                "scan missed a known @Profile(\"!local\") bean — the guard would pass vacuously");
        assertTrue(scanned.contains("com.conductor.integration.connector.local.LocalGscConnector"),
                "scan missed a known @Profile(\"local\") bean — the guard would pass vacuously");

        List<String> offenders = new ArrayList<>();
        scanned.forEach(className -> {
            if (className == null) return;
            Class<?> clazz;
            try {
                clazz = Class.forName(className);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                return;
            }
            Constructor<?>[] ctors = clazz.getDeclaredConstructors();
            if (ctors.length <= 1) return; // single constructor: Spring uses it implicitly
            boolean hasNoArg = Arrays.stream(ctors).anyMatch(c -> c.getParameterCount() == 0);
            boolean hasAutowired = Arrays.stream(ctors).anyMatch(c -> c.isAnnotationPresent(Autowired.class));
            if (!hasAutowired && !hasNoArg) {
                offenders.add(className + " has " + ctors.length
                        + " constructors, none @Autowired and no no-arg fallback");
            }
        });

        assertTrue(offenders.isEmpty(),
                "Spring cannot resolve a constructor for these beans (context refresh will fail at "
                        + "deploy — annotate the intended constructor with @Autowired):\n"
                        + String.join("\n", offenders));
    }

    /** Candidate component class names visible with {@code profile} active ({@code null} = none). */
    private static Set<String> scanFor(String profile) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // @Service/@Repository/@RestController etc. are all meta-annotated with @Component.
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        if (profile != null) {
            scanner.setEnvironment(new MockEnvironment().withProperty("spring.profiles.active", profile));
        }
        Set<String> names = new LinkedHashSet<>();
        scanner.findCandidateComponents("com.conductor")
                .forEach(bd -> names.add(bd.getBeanClassName()));
        return names;
    }
}
