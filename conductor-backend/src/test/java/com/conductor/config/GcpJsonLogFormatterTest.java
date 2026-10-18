package com.conductor.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class GcpJsonLogFormatterTest {

    private final GcpJsonLogFormatter formatter = new GcpJsonLogFormatter();
    private final Logger logger = new LoggerContext().getLogger("com.conductor.Test");

    private LoggingEvent event(Level level, String msg, Throwable t) {
        LoggingEvent e = new LoggingEvent("fqcn", logger, level, msg, t, null);
        e.setThreadName("main");
        return e;
    }

    @Test
    void emitsOneValidJsonLineWithCloudLoggingSeverity() throws Exception {
        LoggingEvent e = event(Level.WARN, "he said \"hi\"\nnext", null);
        e.addKeyValuePair(new KeyValuePair("ms", 42L));
        e.addKeyValuePair(new KeyValuePair("route", "/api/v2/x/{id}"));
        e.addKeyValuePair(new KeyValuePair("preflight", true));

        String line = formatter.format(e);
        assertThat(line).endsWith("\n");
        assertThat(line.trim()).doesNotContain("\n");

        JsonNode json = JsonMapper.builder().build().readTree(line);
        assertThat(json.get("severity").asString()).isEqualTo("WARNING");
        assertThat(json.get("message").asString()).isEqualTo("he said \"hi\"\nnext");
        assertThat(json.get("logger").asString()).isEqualTo("com.conductor.Test");
        assertThat(json.get("thread").asString()).isEqualTo("main");
        assertThat(json.get("timestamp").asString()).isNotBlank();
        assertThat(json.get("ms").isNumber()).isTrue();
        assertThat(json.get("ms").asLong()).isEqualTo(42);
        assertThat(json.get("route").asString()).isEqualTo("/api/v2/x/{id}");
        assertThat(json.get("preflight").asBoolean()).isTrue();
    }

    @Test
    void putsTheStackTraceInBothStackTraceAndMessage() throws Exception {
        LoggingEvent e = event(Level.ERROR, "boom", new IllegalStateException("bad"));
        JsonNode json = JsonMapper.builder().build().readTree(formatter.format(e));
        assertThat(json.get("severity").asString()).isEqualTo("ERROR");
        assertThat(json.get("stack_trace").asString()).contains("IllegalStateException: bad");
        assertThat(json.get("message").asString()).startsWith("boom\n").contains("IllegalStateException: bad");
    }

    @Test
    void mapsLevels() {
        assertThat(GcpJsonLogFormatter.severity(Level.DEBUG)).isEqualTo("DEBUG");
        assertThat(GcpJsonLogFormatter.severity(Level.TRACE)).isEqualTo("DEBUG");
        assertThat(GcpJsonLogFormatter.severity(Level.INFO)).isEqualTo("INFO");
    }
}
