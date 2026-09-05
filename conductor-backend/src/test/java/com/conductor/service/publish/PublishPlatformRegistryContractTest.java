package com.conductor.service.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry is the one vocabulary; three other artefacts spell the same platforms and must agree with it:
 * the OpenAPI {@code platform} enum a client is offered, the MARKETING example's {@code asset_types}, and
 * every connector's shipped tool spec, which is where the registry's action ids have to exist. A platform
 * added to the registry without the rest fails here rather than at a platform.
 */
class PublishPlatformRegistryContractTest {

    private final PublishPlatformRegistry registry = new PublishPlatformRegistry();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void openApiPlatformEnumsMatchTheRegistry() throws Exception {
        Map<String, Object> spec;
        try (InputStream in = getClass().getResourceAsStream("/openapi-v2.yaml")) {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(Integer.MAX_VALUE);
            spec = new Yaml(options).load(in);
        }
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        for (String schema : List.of("PublishTargetSelection", "PublishTargetOption", "PublishTargetResponse")) {
            Map<String, Object> platform = (Map<String, Object>) ((Map<String, Object>)
                    ((Map<String, Object>) schemas.get(schema)).get("properties")).get("platform");
            Object enumValues = platform == null ? null : platform.get("enum");
            if (enumValues == null) {
                continue; // a free string schema documents the vocabulary in prose; nothing to pin
            }
            assertThat((List<String>) enumValues).as(schema + ".platform").containsExactlyElementsOf(registry.ids());
        }
    }

    @Test
    void marketingExampleDeclaresEveryPlatformsAssetType() throws Exception {
        JsonNode marketing;
        try (InputStream in = getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")) {
            marketing = json.readTree(in);
        }
        List<String> assetTypes = new ArrayList<>();
        marketing.path("asset_types").forEach(node -> assetTypes.add(node.asText()));
        assertThat(assetTypes).containsExactlyElementsOf(
                registry.all().stream().map(PublishPlatform::assetType).toList());
    }

    @Test
    void everyRegistryActionExistsInItsConnectorsToolSpec() throws Exception {
        for (PublishPlatform platform : registry.all()) {
            Set<String> declared = actionIds(platform.connectorId());
            assertThat(declared).as(platform.id() + " publish").contains(platform.publish().actionId());
            if (platform.revoke() != null) {
                assertThat(declared).as(platform.id() + " revoke").contains(platform.revoke().actionId());
            }
            if (platform.confirm() != null) {
                assertThat(declared).as(platform.id() + " confirm").contains(platform.confirm().actionId());
            }
            assertThat(platform.isNative()).as(platform.id() + " native platforms carry revoke and confirm")
                    .isEqualTo(platform.revoke() != null && platform.confirm() != null);
        }
    }

    private Set<String> actionIds(String connectorId) throws Exception {
        JsonNode spec;
        try (InputStream in = getClass().getResourceAsStream("/connectors/tool-specs/" + connectorId + ".json")) {
            assertThat(in).as("tool spec for connector " + connectorId).isNotNull();
            spec = json.readTree(in);
        }
        Set<String> ids = new java.util.HashSet<>();
        spec.path("actions").forEach(action -> ids.add(action.path("id").asText()));
        return ids;
    }
}
