package com.conductor.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundUrlGuardTest {

    private final OutboundUrlGuard strict = new OutboundUrlGuard(false);

    @Test
    void rejectsPlainHttp() {
        assertThatThrownBy(() -> strict.validate("http://example.com/mcp"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https://");
    }

    @Test
    void rejectsLoopbackPrivateAndMetadataAddresses() {
        for (String url : new String[] {
                "https://127.0.0.1/mcp", "https://10.1.2.3/mcp",
                "https://192.168.0.5/mcp", "https://172.16.0.1/mcp", "https://169.254.169.254/latest",
                "https://[::1]/mcp", "https://[fd00::1]/mcp", "https://100.64.0.1/mcp", "https://0.0.0.0/mcp"}) {
            assertThatThrownBy(() -> strict.validate(url))
                    .as(url).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private or internal");
        }
    }

    @Test
    void rejectsHostnameResolvingToPrivateAddress() {
        // localhost resolves to a loopback address on every machine.
        assertThatThrownBy(() -> strict.validate("https://localhost:8443/mcp"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private or internal");
    }

    @Test
    void rejectsCredentialsInUrlAndGarbage() {
        assertThatThrownBy(() -> strict.validate("https://user:pw@8.8.8.8/mcp"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.validate("not a url")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.validate(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsPublicHttpsAddress() {
        assertThat(strict.validate("https://8.8.8.8/mcp").getHost()).isEqualTo("8.8.8.8");
    }

    @Test
    void permissiveModeAllowsHttpLocalhost() {
        assertThat(new OutboundUrlGuard(true).validate("http://127.0.0.1:8080/mcp")).isNotNull();
    }
}
