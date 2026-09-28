package com.conductor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Binds path and query parameters to OpenAPI-generated enums by their wire value.
 *
 * <p>Spring's default {@code String → Enum} conversion is {@link Enum#valueOf}, which matches the Java constant
 * name. A generated enum's constant is the upper-cased value ({@code MARK}) while the wire value stays as the
 * spec declares it ({@code mark}), so a lower-case enum in a path such as
 * {@code /brand-kits/{kitId}/images/{slot}} never bound. Every generated enum carries a static
 * {@code fromValue(String)}; this factory uses it, and falls back to {@code valueOf} for any enum without one.
 */
@Configuration
public class GeneratedEnumConverterConfig implements WebMvcConfigurer {

    private static final String GENERATED_PKG = "com.conductor.generated";

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new GeneratedEnumConverterFactory());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class GeneratedEnumConverterFactory implements ConverterFactory<String, Enum> {

        @Override
        public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
            Method fromValue = targetType.getPackageName().startsWith(GENERATED_PKG) ? findFromValue(targetType) : null;
            return source -> {
                String value = source.trim();
                if (value.isEmpty()) {
                    return null;
                }
                if (fromValue != null) {
                    try {
                        return (T) fromValue.invoke(null, value);
                    } catch (ReflectiveOperationException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        throw new IllegalArgumentException(cause.getMessage(), cause);
                    }
                }
                return (T) Enum.valueOf(targetType, value);
            };
        }

        private static Method findFromValue(Class<?> type) {
            try {
                Method m = type.getMethod("fromValue", String.class);
                return Modifier.isStatic(m.getModifiers()) ? m : null;
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }
}
