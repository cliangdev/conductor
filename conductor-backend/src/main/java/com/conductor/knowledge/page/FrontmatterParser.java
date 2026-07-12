package com.conductor.knowledge.page;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single parser/renderer for OKF page documents -- markdown with a leading YAML frontmatter block
 * ({@code ---\n...\n---}). Mirrors {@code com.conductor.workflow.model.WorkflowYamlParser}'s role as the
 * only class in this package that imports SnakeYAML directly.
 *
 * <p>Frontmatter must declare a string {@code type}; everything else (including unknown keys) round-trips
 * verbatim through {@link #parse} / {@link #render} so the librarian can preserve fields it doesn't
 * understand. A document with no frontmatter at all is invalid -- every page needs a {@code type}.
 */
@Component
public class FrontmatterParser {

    private static final String DELIMITER = "---";

    private static final DumperOptions DUMPER_OPTIONS = new DumperOptions();
    static {
        DUMPER_OPTIONS.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        DUMPER_OPTIONS.setPrettyFlow(true);
    }

    /** Parsed frontmatter (order-preserving map) and body, exactly as required to reconstruct the source via {@link #render}. */
    public record Parsed(Map<String, Object> frontmatter, String body) {

        public String type() {
            return (String) frontmatter.get("type");
        }

        public String title() {
            Object v = frontmatter.get("title");
            return v instanceof String s ? s : null;
        }

        public String description() {
            Object v = frontmatter.get("description");
            return v instanceof String s ? s : null;
        }
    }

    public Parsed parse(String document) {
        if (document == null) {
            throw new FrontmatterException("Document is empty -- every page needs a frontmatter 'type'");
        }
        String[] lines = document.split("\n", -1);
        if (lines.length == 0 || !lines[0].equals(DELIMITER)) {
            throw new FrontmatterException("Missing frontmatter -- document must start with '---'");
        }
        int closeIndex = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].equals(DELIMITER)) {
                closeIndex = i;
                break;
            }
        }
        if (closeIndex < 0) {
            throw new FrontmatterException("Unterminated frontmatter -- missing closing '---'");
        }

        String yamlBlock = String.join("\n", Arrays.copyOfRange(lines, 1, closeIndex));
        Map<String, Object> frontmatter;
        try {
            Object loaded = new Yaml().load(yamlBlock);
            if (loaded == null) {
                frontmatter = new LinkedHashMap<>();
            } else if (loaded instanceof Map<?, ?> m) {
                frontmatter = new LinkedHashMap<>();
                m.forEach((k, v) -> frontmatter.put(String.valueOf(k), v));
            } else {
                throw new FrontmatterException("Frontmatter must be a YAML mapping");
            }
        } catch (YAMLException e) {
            throw new FrontmatterException("Invalid frontmatter YAML: " + e.getMessage());
        }

        Object type = frontmatter.get("type");
        if (!(type instanceof String typeStr) || typeStr.isBlank()) {
            throw new FrontmatterException("Frontmatter 'type' is required");
        }

        int bodyStart = closeIndex + 1;
        if (bodyStart < lines.length && lines[bodyStart].isEmpty()) {
            bodyStart++;
        }
        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart, lines.length));

        return new Parsed(frontmatter, body);
    }

    /** Canonical re-render: {@code ---\n<yaml>---\n\n<body>}. {@link #parse} on the output reproduces {@code frontmatter}/{@code body}. */
    public String render(Map<String, Object> frontmatter, String body) {
        String yaml = new Yaml(DUMPER_OPTIONS).dump(frontmatter);
        return DELIMITER + "\n" + yaml + DELIMITER + "\n\n" + (body == null ? "" : body);
    }
}
