package com.conductor.observability;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RequestMetricsFilterTest {

    @SuppressWarnings("unchecked")
    private final RequestMetricsFilter filter = new RequestMetricsFilter(mock(ObjectProvider.class), 1000);

    @Test
    void setsServerTimingWithTheQueriesTheRequestRan() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/work-items/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v2/work-items/{id}");
            RequestStats.recordQuery("select * from work_items where id = 1", 2_000_000, 1);
            RequestStats.recordQuery("select * from work_items where id = 2", 3_000_000, 1);
            RequestStats.recordConnectionCheckout(1_000_000);
            ((HttpServletResponse) res).getOutputStream().write("{}".getBytes());
        });

        assertThat(response.getHeader("Server-Timing"))
                .startsWith("db;dur=5;desc=\"2 queries\", pool;dur=1, sign;dur=0, gcs;dur=0, app;dur=");
        assertThat(response.getHeader("Timing-Allow-Origin")).isEqualTo("*");
        assertThat(RequestStats.get()).as("scope closed after the request").isNull();
    }

    @Test
    void headerIsSetEvenWhenTheHandlerNeverWritesABody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("DELETE", "/api/v2/x"), response, (req, res) -> { });
        assertThat(response.getHeader("Server-Timing")).contains("db;dur=0;desc=\"0 queries\"");
    }

    @Test
    void headerIsSetBeforeSendErrorCommits() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v2/x"), response, (req, res) ->
                ((HttpServletResponse) res).sendError(401, "Unauthorized"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("Server-Timing")).isNotNull();
    }

    @Test
    void passesTheBodyThroughUnchanged() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v2/x"), response, (req, res) -> {
            var out = ((HttpServletResponse) res).getOutputStream();
            out.write(new byte[100]);
            out.write(7);
        });
        assertThat(response.getContentAsByteArray()).hasSize(101);
    }

    @Test
    void handlesPreflightWithoutAMatchedRoute() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("OPTIONS", "/nope"), response, (req, res) -> { });
        assertThat(response.getHeader("Server-Timing")).isNotNull();
    }
}
