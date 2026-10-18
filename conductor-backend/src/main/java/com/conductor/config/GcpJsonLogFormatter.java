package com.conductor.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import java.util.List;
import java.util.Set;

/**
 * One JSON object per line in the shape Cloud Logging parses into {@code jsonPayload}, with
 * {@code severity} mapped to Cloud Logging's level names. Enabled with
 * {@code logging.structured.format.console=com.conductor.config.GcpJsonLogFormatter}.
 *
 * <p>An exception's stack trace is written to {@code stack_trace} and also appended to
 * {@code message}, which is where Error Reporting looks for it. SLF4J key-value pairs
 * ({@code log.atInfo().addKeyValue(..)}) become top-level fields; numbers and booleans stay typed so
 * they can be aggregated in Log Analytics and {@code jq}.
 */
public class GcpJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final Set<String> RESERVED = Set.of("severity", "message", "logger", "thread", "timestamp", "stack_trace");

    @Override
    public String format(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "timestamp", event.getInstant().toString());
        sb.append(',');
        field(sb, "severity", severity(event.getLevel()));

        String stack = event.getThrowableProxy() != null ? ThrowableProxyUtil.asString(event.getThrowableProxy()) : null;
        String message = event.getFormattedMessage();
        sb.append(',');
        field(sb, "message", stack != null ? message + "\n" + stack : message);
        sb.append(',');
        field(sb, "logger", event.getLoggerName());
        sb.append(',');
        field(sb, "thread", event.getThreadName());
        if (stack != null) {
            sb.append(',');
            field(sb, "stack_trace", stack);
        }

        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair kv : pairs) {
                if (kv.key == null || RESERVED.contains(kv.key)) {
                    continue;
                }
                sb.append(',');
                quote(sb, kv.key);
                sb.append(':');
                value(sb, kv.value);
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    static String severity(Level level) {
        return switch (level.toInt()) {
            case Level.ERROR_INT -> "ERROR";
            case Level.WARN_INT -> "WARNING";
            case Level.INFO_INT -> "INFO";
            default -> "DEBUG";
        };
    }

    private static void field(StringBuilder sb, String key, String value) {
        quote(sb, key);
        sb.append(':');
        quote(sb, value);
    }

    private static void value(StringBuilder sb, Object value) {
        if (value instanceof Number n && isFinite(n)) {
            sb.append(n);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value == null) {
            sb.append("null");
        } else {
            quote(sb, String.valueOf(value));
        }
    }

    private static boolean isFinite(Number n) {
        double d = n.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static void quote(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
