package com.conductor.service;

import com.conductor.exception.BusinessException;
import com.conductor.exception.MissingFieldsException;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkItemFieldsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Statechart changes() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/marketing-changes.workflow.json")) {
            return Statechart.parse(mapper.readTree(in));
        }
    }

    private Statechart noFields() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/engineering.workflow.json")) {
            return Statechart.parse(mapper.readTree(in));
        }
    }

    @Test
    void parsesFieldsAndDueLabelFromTheDefinition() throws Exception {
        Statechart chart = changes();
        assertThat(chart.dueLabel()).isEqualTo("Review on");
        assertThat(chart.fields()).extracting("key").containsExactly(
                "hypothesis", "baseline", "control", "liveSince", "outcome", "verdict", "lesson", "evidence");
        assertThat(chart.field("verdict").orElseThrow().options())
                .containsExactly("worked", "did_not_work", "inconclusive");
        assertThat(chart.field("liveSince").orElseThrow().requiredIn()).containsExactly("LIVE", "REVIEWED");
    }

    @Test
    void storesValidValuesOfEveryDeclaredType() throws Exception {
        JsonNode merged = WorkItemFields.merge(changes(), null, Map.of(
                "hypothesis", "A shorter title lifts clicks",
                "liveSince", "2026-10-01",
                "verdict", "worked"));
        assertThat(merged.get("hypothesis").asText()).isEqualTo("A shorter title lifts clicks");
        assertThat(merged.get("liveSince").asText()).isEqualTo("2026-10-01");
        assertThat(merged.get("verdict").asText()).isEqualTo("worked");
    }

    @Test
    void rejectsUnknownKeysListingTheValidOnes() throws Exception {
        assertThatThrownBy(() -> WorkItemFields.merge(changes(), null, Map.of("mood", "good")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unknown field 'mood'")
                .hasMessageContaining("hypothesis");
    }

    @Test
    void rejectsTypeMismatches() throws Exception {
        Statechart chart = changes();
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("liveSince", "10/01/2026")))
                .hasMessageContaining("'liveSince' must be a date");
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("liveSince", "2026-02-30")))
                .hasMessageContaining("'liveSince' must be a date");
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("verdict", "great")))
                .hasMessageContaining("'verdict' must be one of");
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("hypothesis", 42)))
                .hasMessageContaining("'hypothesis' must be text");
    }

    @Test
    void validatesNumberAndUrlTypes() throws Exception {
        Statechart chart = Statechart.parse(mapper.readTree("""
                {"id": "X", "fields": [
                  {"key": "budget", "label": "Budget", "type": "number"},
                  {"key": "link", "label": "Link", "type": "url"}]}
                """));
        JsonNode ok = WorkItemFields.merge(chart, null, Map.of("budget", 12.5, "link", "https://example.com/a"));
        assertThat(ok.get("budget").asDouble()).isEqualTo(12.5);
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("budget", "12")))
                .hasMessageContaining("'budget' must be a number");
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("link", "javascript:alert(1)")))
                .hasMessageContaining("'link' must be an http(s) URL");
        assertThatThrownBy(() -> WorkItemFields.merge(chart, null, Map.of("link", "example.com")))
                .hasMessageContaining("'link' must be an http(s) URL");
    }

    @Test
    void aWorkflowWithoutFieldsRejectsAnyPayload() throws Exception {
        assertThatThrownBy(() -> WorkItemFields.merge(noFields(), null, Map.of("hypothesis", "x")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("defines no fields");
    }

    @Test
    void mergesKeyByKeyAndNullClearsAKey() throws Exception {
        JsonNode existing = mapper.readTree("{\"hypothesis\": \"old\", \"control\": \"page A\"}");
        Map<String, Object> patch = new HashMap<>();
        patch.put("hypothesis", "new");
        patch.put("control", null);
        patch.put("baseline", "10 clicks");

        JsonNode merged = WorkItemFields.merge(changes(), existing, patch);

        assertThat(merged.get("hypothesis").asText()).isEqualTo("new");
        assertThat(merged.has("control")).isFalse();
        assertThat(merged.get("baseline").asText()).isEqualTo("10 clicks");
        // The stored object passed in is not mutated.
        assertThat(existing.get("hypothesis").asText()).isEqualTo("old");
    }

    @Test
    void clearingTheLastKeyStoresNothing() throws Exception {
        JsonNode existing = mapper.readTree("{\"control\": \"page A\"}");
        Map<String, Object> patch = new HashMap<>();
        patch.put("control", "");
        assertThat(WorkItemFields.merge(changes(), existing, patch)).isNull();
    }

    @Test
    void anAbsentPayloadLeavesTheStoredValuesAlone() throws Exception {
        JsonNode existing = mapper.readTree("{\"control\": \"page A\"}");
        assertThat(WorkItemFields.merge(changes(), existing, null)).isSameAs(existing);
    }

    @Test
    void listsRequiredFieldsMissingForAStatusInDeclaredOrder() throws Exception {
        Statechart chart = changes();
        JsonNode partial = mapper.readTree("{\"liveSince\": \"2026-10-01\", \"verdict\": \"worked\"}");

        assertThat(WorkItemFields.missingRequired(chart, partial, "REVIEWED")).containsExactly("outcome", "lesson");
        assertThat(WorkItemFields.missingRequired(chart, partial, "LIVE")).isEmpty();
        assertThat(WorkItemFields.missingRequired(chart, null, "LIVE")).containsExactly("liveSince");
        assertThat(WorkItemFields.missingRequired(chart, null, "ABANDONED")).isEmpty();
    }

    @Test
    void requireFilledThrowsWithTheMissingKeys() throws Exception {
        assertThatThrownBy(() -> WorkItemFields.requireFilled(changes(), null, "REVIEWED"))
                .isInstanceOfSatisfying(MissingFieldsException.class, e ->
                        assertThat(e.getMissingFields())
                                .isEqualTo(List.of("liveSince", "outcome", "verdict", "lesson")));
    }
}
