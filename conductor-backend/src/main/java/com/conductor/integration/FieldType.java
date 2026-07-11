package com.conductor.integration;

/** Render hint for a {@link ConnectorConfigField}. */
public enum FieldType {
    STRING,
    SECRET,        // masked input / never returned
    SELECT,
    MULTISELECT,
    BOOLEAN,
    URL_READONLY,  // generated, read-only, copy-to-clipboard (e.g. a webhook URL)
    JSON           // multiline JSON input, rendered as a textarea (e.g. a GCP service-account key)
}
