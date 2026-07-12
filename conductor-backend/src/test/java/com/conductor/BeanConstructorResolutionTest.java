package com.conductor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the deploy-only bean-wiring failure class: a Spring-managed class with MULTIPLE
 * constructors and no {@code @Autowired} makes Spring fall back to a (usually nonexistent) no-arg
 * constructor, failing context refresh. {@code @Profile("!local")} beans are never instantiated by
 * the test suite, so without this scan the failure only surfaces when a real deploy boots the
 * container (it took down the 2026-07-12 preview deploy via {@code DiscordActionConnector}'s
 * package-private test constructor).
 *
 * <p>Reflection-only — no Spring context, so it covers every profile's beans uniformly.
 */
class BeanConstructorResolutionTest {

    @Test
    void everyComponentHasAnUnambiguousConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        // @Service/@Repository/@RestController etc. are all meta-annotated with @Component.
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> offenders = new ArrayList<>();
        scanner.findCandidateComponents("com.conductor").forEach(bd -> {
            String className = bd.getBeanClassName();
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
}
