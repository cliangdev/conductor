package com.conductor.integration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Small factory for the {@link RestTemplate} connectors use to call third-party APIs. Centralizes the
 * connect/read timeout so the value isn't copy-pasted across every connector.
 */
public final class ConnectorHttp {

    /** Default per-connector timeout: external APIs run behind the framework's own fetch timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(8);

    private ConnectorHttp() {}

    public static RestTemplate restTemplate() {
        return restTemplate(DEFAULT_TIMEOUT);
    }

    public static RestTemplate restTemplate(Duration timeout) {
        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        restTemplate.setRequestFactory(factory);
        return restTemplate;
    }
}
