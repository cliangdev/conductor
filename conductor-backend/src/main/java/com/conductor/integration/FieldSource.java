package com.conductor.integration;

/**
 * Who supplies a config field's value.
 * USER_INPUT — the user types it (API key, repo name).
 * GENERATED  — the framework generates it and displays it read-only (webhook URL + signing secret).
 */
public enum FieldSource { USER_INPUT, GENERATED }
