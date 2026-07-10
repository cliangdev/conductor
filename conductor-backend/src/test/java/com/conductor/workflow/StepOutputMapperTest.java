package com.conductor.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepOutputMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void applyDeclaredOutputs_extractsTopLevelField() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("summary", "All good");

        Map<String, String> outputs = new HashMap<>();
        Map<String, Object> stepDef = Map.of("outputs", Map.of("result", "body.summary"));

        StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);

        assertThat(outputs.get("result")).isEqualTo("All good");
    }

    @Test
    void applyDeclaredOutputs_extractsNestedField() {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode nested = body.putObject("data");
        nested.put("count", 42);

        Map<String, String> outputs = new HashMap<>();
        Map<String, Object> stepDef = Map.of("outputs", Map.of("total", "body.data.count"));

        StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);

        assertThat(outputs.get("total")).isEqualTo("42");
    }

    @Test
    void applyDeclaredOutputs_pathWithoutBodyPrefixResolvesTheSameWay() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("field", "value");

        Map<String, String> outputs = new HashMap<>();
        Map<String, Object> stepDef = Map.of("outputs", Map.of("out", "field"));

        StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);

        assertThat(outputs.get("out")).isEqualTo("value");
    }

    @Test
    void applyDeclaredOutputs_missingPathIsSkipped() {
        ObjectNode body = objectMapper.createObjectNode();

        Map<String, String> outputs = new HashMap<>();
        Map<String, Object> stepDef = Map.of("outputs", Map.of("out", "body.missing"));

        StepOutputMapper.applyDeclaredOutputs(stepDef, body, outputs);

        assertThat(outputs).doesNotContainKey("out");
    }

    @Test
    void applyDeclaredOutputs_noOutputsBlockIsNoOp() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("field", "value");

        Map<String, String> outputs = new HashMap<>();
        StepOutputMapper.applyDeclaredOutputs(Map.of(), body, outputs);

        assertThat(outputs).isEmpty();
    }

    @Test
    void extractJsonPath_returnsNullForNullPath() {
        assertThat(StepOutputMapper.extractJsonPath(objectMapper.createObjectNode(), null)).isNull();
    }
}
