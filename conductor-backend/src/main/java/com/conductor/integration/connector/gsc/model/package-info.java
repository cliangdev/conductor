/**
 * Java records mirroring the Google Search Console API (webmasters/v3).
 *
 * Canonical spec / discovery document:
 *   https://www.googleapis.com/discovery/v1/apis/webmasters/v3/rest
 * API reference:
 *   https://developers.google.com/webmaster-tools/v1/api_reference_index
 *
 * These are anti-corruption-layer DTOs — external API concepts do not cross the connector
 * boundary. ConnectorData.data is always Map<String, Object> for callers.
 */
package com.conductor.integration.connector.gsc.model;
