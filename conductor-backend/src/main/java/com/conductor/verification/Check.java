package com.conductor.verification;

/**
 * One named probe outcome in a verification report — the value type shared by the per-provider
 * preflights ({@code agent.provider}, {@code workflow}) and the orchestrating
 * {@code service.ProviderVerificationService}, kept in its own leaf package so the probes don't have
 * to import a type owned by the orchestrator that calls them.
 */
public record Check(String name, CheckStatus status, String message) {}
