package com.conductor.observability;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.Map;

/**
 * Logs one {@code http_request} line per request (route, status, latency, where the time went) and
 * sets a {@code Server-Timing} header so the same breakdown is visible in browser devtools and to the
 * frontend's telemetry.
 *
 * <p>Runs at highest precedence, ahead of the Spring Security chain, so auth filters (and their DB
 * lookups) are inside the measured span.
 *
 * <p><b>Server-Timing and the commit point.</b> A header only takes effect if set before the response
 * commits, and we cannot buffer the body to find out the final timings. {@link MetricsResponse} instead
 * calls {@link #applyHeaders} lazily, immediately before the first write, flush, close, {@code sendError}
 * or {@code sendRedirect} - whichever comes first. By then the controller has already done its DB, GCS
 * and signing work (the dominant cost); only response serialization after that point is excluded from
 * the {@code app} figure. Responses that never write a body get the header after the chain returns. No
 * response bytes are buffered: the wrapper only counts them as they pass.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "observability.request-metrics.enabled", havingValue = "true", matchIfMissing = true)
public class RequestMetricsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestMetricsFilter.class);
    private static final String HEALTH_PATH = "/api/v1/health";

    private final ObjectProvider<DataSource> dataSource;
    private final long slowRequestMs;

    public RequestMetricsFilter(ObjectProvider<DataSource> dataSource,
                                @Value("${observability.slow-request-ms:1000}") long slowRequestMs) {
        this.dataSource = dataSource;
        this.slowRequestMs = slowRequestMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try (RequestStats.Scope scope = RequestStats.start()) {
            RequestStats stats = scope.stats();
            MetricsResponse wrapped = new MetricsResponse(response, () -> applyHeaders(response, stats, start));
            try {
                chain.doFilter(request, wrapped);
            } finally {
                wrapped.ensureHeaders();
                logRequest(request, response, wrapped, stats, start);
            }
        }
    }

    private void applyHeaders(HttpServletResponse response, RequestStats stats, long startNanos) {
        if (response.isCommitted()) {
            return;
        }
        response.setHeader("Server-Timing", serverTiming(stats, RequestStats.toMs(System.nanoTime() - startNanos)));
        response.setHeader("Timing-Allow-Origin", "*");
    }

    /** {@code db;dur=X;desc="N queries", pool;dur=Y, sign;dur=Z, gcs;dur=W, app;dur=TOTAL} */
    static String serverTiming(RequestStats stats, long totalMs) {
        return "db;dur=" + RequestStats.toMs(stats.dbNanos()) + ";desc=\"" + stats.dbQueries() + " queries\""
                + ", pool;dur=" + RequestStats.toMs(stats.poolWaitNanos())
                + ", sign;dur=" + RequestStats.toMs(stats.signNanos())
                + ", gcs;dur=" + RequestStats.toMs(stats.gcsNanos())
                + ", app;dur=" + totalMs;
    }

    private void logRequest(HttpServletRequest request, HttpServletResponse response, MetricsResponse wrapped,
                            RequestStats stats, long startNanos) {
        String path = request.getRequestURI();
        if (path != null && path.endsWith(HEALTH_PATH)) {
            return;
        }
        try {
            long ms = RequestStats.toMs(System.nanoTime() - startNanos);
            boolean slow = ms > slowRequestMs;
            LoggingEventBuilder builder = slow ? log.atWarn() : log.atInfo();
            addFields(builder, request, response, wrapped, stats, ms);
            if (slow) {
                StringBuilder top = new StringBuilder();
                for (Map.Entry<String, Integer> e : stats.topRepeatedSql(5)) {
                    if (top.length() > 0) {
                        top.append(" | ");
                    }
                    top.append(e.getValue()).append("x ").append(e.getKey());
                }
                builder.addKeyValue("topSql", top.toString());
                builder.log("slow_request");
            } else {
                builder.log("http_request");
            }
        } catch (RuntimeException e) {
            log.debug("request metrics logging failed", e);
        }
    }

    private void addFields(LoggingEventBuilder b, HttpServletRequest request, HttpServletResponse response,
                           MetricsResponse wrapped, RequestStats stats, long ms) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        b.addKeyValue("route", pattern != null ? pattern.toString() : "unmatched")
                .addKeyValue("method", request.getMethod())
                .addKeyValue("status", response.getStatus())
                .addKeyValue("ms", ms)
                .addKeyValue("respBytes", responseBytes(response, wrapped))
                .addKeyValue("dbQueries", stats.dbQueries())
                .addKeyValue("dbMs", RequestStats.toMs(stats.dbNanos()))
                .addKeyValue("poolWaitMs", RequestStats.toMs(stats.poolWaitNanos()))
                .addKeyValue("connCheckouts", stats.connCheckouts())
                .addKeyValue("signedUrls", stats.signedUrls())
                .addKeyValue("signMs", RequestStats.toMs(stats.signNanos()))
                .addKeyValue("gcsCalls", stats.gcsCalls())
                .addKeyValue("gcsMs", RequestStats.toMs(stats.gcsNanos()));
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            b.addKeyValue("preflight", true);
        }
        addPoolFields(b);
    }

    private static long responseBytes(HttpServletResponse response, MetricsResponse wrapped) {
        String contentLength = response.getHeader("Content-Length");
        if (contentLength != null) {
            try {
                return Long.parseLong(contentLength.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the counted size
            }
        }
        return wrapped.bytesWritten();
    }

    private void addPoolFields(LoggingEventBuilder b) {
        try {
            DataSource ds = dataSource.getIfAvailable();
            if (ds != null && ds.isWrapperFor(HikariDataSource.class)) {
                HikariPoolMXBean pool = ds.unwrap(HikariDataSource.class).getHikariPoolMXBean();
                if (pool != null) {
                    b.addKeyValue("poolActive", pool.getActiveConnections())
                            .addKeyValue("poolIdle", pool.getIdleConnections())
                            .addKeyValue("poolWaiting", pool.getThreadsAwaitingConnection());
                }
            }
        } catch (Exception | LinkageError ignored) {
            // pool stats are best-effort; the rest of the line is still useful
        }
    }

    /**
     * Counts bytes as they pass and runs {@code beforeCommit} once, just before the response could
     * commit. Nothing is buffered.
     */
    static final class MetricsResponse extends HttpServletResponseWrapper {
        private final Runnable beforeCommit;
        private boolean headersApplied;
        private long bytes;
        private ServletOutputStream stream;
        private PrintWriter writer;

        MetricsResponse(HttpServletResponse response, Runnable beforeCommit) {
            super(response);
            this.beforeCommit = beforeCommit;
        }

        long bytesWritten() {
            return bytes;
        }

        void ensureHeaders() {
            if (!headersApplied) {
                headersApplied = true;
                beforeCommit.run();
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getOutputStream());
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new CountingWriter(super.getWriter()));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            ensureHeaders();
            super.flushBuffer();
        }

        @Override
        public void sendError(int sc) throws IOException {
            ensureHeaders();
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            ensureHeaders();
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            ensureHeaders();
            super.sendRedirect(location);
        }

        @Override
        public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
            ensureHeaders();
            super.sendRedirect(location, sc, clearBuffer);
        }

        private final class CountingStream extends ServletOutputStream {
            private final ServletOutputStream delegate;

            CountingStream(ServletOutputStream delegate) {
                this.delegate = delegate;
            }

            @Override
            public void write(int b) throws IOException {
                ensureHeaders();
                delegate.write(b);
                bytes++;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                ensureHeaders();
                delegate.write(b, off, len);
                bytes += len;
            }

            @Override
            public void flush() throws IOException {
                ensureHeaders();
                delegate.flush();
            }

            @Override
            public void close() throws IOException {
                ensureHeaders();
                delegate.close();
            }

            @Override
            public boolean isReady() {
                return delegate.isReady();
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                delegate.setWriteListener(listener);
            }
        }

        /** Counts characters (an approximation of bytes for text) for the rare handler that uses getWriter(). */
        private final class CountingWriter extends Writer {
            private final PrintWriter delegate;

            CountingWriter(PrintWriter delegate) {
                this.delegate = delegate;
            }

            @Override
            public void write(char[] cbuf, int off, int len) {
                ensureHeaders();
                delegate.write(cbuf, off, len);
                bytes += len;
            }

            @Override
            public void flush() {
                ensureHeaders();
                delegate.flush();
            }

            @Override
            public void close() {
                ensureHeaders();
                delegate.close();
            }
        }
    }
}
