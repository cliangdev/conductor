package com.conductor.integration;

import java.util.List;

/** Describes an outbound action a connector exposes (forward-looking seam). */
public record ActionDescriptor(String id, String label, List<String> inputKeys) {}
