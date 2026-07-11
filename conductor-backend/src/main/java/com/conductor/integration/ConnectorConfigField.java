package com.conductor.integration;

/**
 * One field in a connector's config form. The same mechanism drives user-input fields (API key,
 * repo name) and framework-generated read-only fields (webhook URL + signing secret).
 */
public record ConnectorConfigField(String key, String label, String hint,
                                   FieldType type, FieldSource source, boolean required) {

    /**
     * Convenience: secret fields are masked in the UI and never returned in read responses.
     * JSON fields carry credential material too (e.g. a GCP service-account key) and land in the same
     * encrypted token slot as SECRET fields, so they're treated as secret as well.
     */
    public boolean secret() { return type == FieldType.SECRET || type == FieldType.JSON; }

    public static ConnectorConfigField userInput(String key, String label, String hint,
                                                 FieldType type, boolean required) {
        return new ConnectorConfigField(key, label, hint, type, FieldSource.USER_INPUT, required);
    }

    public static ConnectorConfigField generated(String key, String label, String hint, FieldType type) {
        return new ConnectorConfigField(key, label, hint, type, FieldSource.GENERATED, false);
    }
}
