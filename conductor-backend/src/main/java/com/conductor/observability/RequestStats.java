package com.conductor.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-request (or per-scheduled-job) accumulator of where time went: database statements, connection
 * pool waits, GCS signing and GCS calls.
 *
 * <p>Lives in a {@link ThreadLocal} for the span of one {@link Scope}. All {@code record*} helpers are
 * static and silently do nothing when no scope is open on the calling thread, so instrumented code
 * (the datasource proxy, {@code GcpStorageService}) is safe on any thread. Work that hops to another
 * thread is not counted. The counters are plain fields: a scope is only ever touched by its own thread.
 */
public final class RequestStats {

    /** Distinct normalized statements tracked per scope; the rest fold into {@link #OTHER_SQL}. */
    static final int MAX_DISTINCT_SQL = 200;
    static final int MAX_SQL_LENGTH = 300;
    static final String OTHER_SQL = "(other statements)";

    private static final ThreadLocal<RequestStats> CURRENT = new ThreadLocal<>();

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER_LITERAL = Pattern.compile("(?<![\\w$.])-?\\d+(?:\\.\\d+)?(?![\\w$])");
    private static final Pattern IN_LIST = Pattern.compile("(?i)\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");
    private static final Pattern VALUES_LIST = Pattern.compile(
            "(?i)\\bvalues\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)(?:\\s*,\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\))+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private int dbQueries;
    private long dbNanos;
    private long poolWaitNanos;
    private int connCheckouts;
    private int signedUrls;
    private long signNanos;
    private int gcsCalls;
    private long gcsNanos;
    private final Map<String, Integer> sqlCounts = new LinkedHashMap<>();

    private RequestStats() {
    }

    /** Opens a scope on this thread. Close it to restore whatever scope was open before. */
    public static Scope start() {
        RequestStats previous = CURRENT.get();
        RequestStats stats = new RequestStats();
        CURRENT.set(stats);
        return new Scope(stats, previous);
    }

    /** The stats of the scope open on this thread, or {@code null}. */
    public static RequestStats get() {
        return CURRENT.get();
    }

    /** Drops any scope on this thread. Scopes normally close themselves. */
    public static void clear() {
        CURRENT.remove();
    }

    public static void recordQuery(String sql, long nanos, int statements) {
        RequestStats s = CURRENT.get();
        if (s == null) {
            return;
        }
        s.dbQueries += statements;
        s.dbNanos += nanos;
        s.countSql(normalizeSql(sql), statements);
    }

    /** A further row of a JDBC batch already counted as one query: listed in the SQL counts, not in dbQueries. */
    public static void recordBatchedRow(String sql) {
        RequestStats s = CURRENT.get();
        if (s == null) {
            return;
        }
        s.countSql(normalizeSql(sql), 1);
    }

    public static void recordConnectionCheckout(long nanos) {
        RequestStats s = CURRENT.get();
        if (s == null) {
            return;
        }
        s.connCheckouts++;
        s.poolWaitNanos += nanos;
    }

    public static void recordSignedUrl(long nanos) {
        RequestStats s = CURRENT.get();
        if (s == null) {
            return;
        }
        s.signedUrls++;
        s.signNanos += nanos;
    }

    public static void recordGcsCall(long nanos) {
        RequestStats s = CURRENT.get();
        if (s == null) {
            return;
        }
        s.gcsCalls++;
        s.gcsNanos += nanos;
    }

    private void countSql(String normalized, int statements) {
        String key = normalized;
        if (!sqlCounts.containsKey(key) && sqlCounts.size() >= MAX_DISTINCT_SQL) {
            key = OTHER_SQL;
        }
        sqlCounts.merge(key, statements, Integer::sum);
    }

    /**
     * Collapses literals and whitespace so repeated statements with different parameters group
     * together: string and numeric literals become {@code ?}, {@code IN (?, ?, ?)} and multi-row
     * {@code VALUES} lists shrink to one placeholder group, and the result is capped in length.
     */
    public static String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        String s = STRING_LITERAL.matcher(sql).replaceAll("?");
        s = NUMBER_LITERAL.matcher(s).replaceAll("?");
        s = VALUES_LIST.matcher(s).replaceAll("values (?)");
        s = IN_LIST.matcher(s).replaceAll("in (?)");
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s.length() > MAX_SQL_LENGTH ? s.substring(0, MAX_SQL_LENGTH) + "..." : s;
    }

    public int dbQueries() { return dbQueries; }
    public long dbNanos() { return dbNanos; }
    public long poolWaitNanos() { return poolWaitNanos; }
    public int connCheckouts() { return connCheckouts; }
    public int signedUrls() { return signedUrls; }
    public long signNanos() { return signNanos; }
    public int gcsCalls() { return gcsCalls; }
    public long gcsNanos() { return gcsNanos; }

    public static long toMs(long nanos) {
        return nanos / 1_000_000L;
    }

    /** Normalized SQL repeated more than once, most frequent first, at most {@code limit} entries. */
    public List<Map.Entry<String, Integer>> topRepeatedSql(int limit) {
        return sqlCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1 && !OTHER_SQL.equals(e.getKey()))
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .toList();
    }

    /** Normalized SQL to execution count (a copy). */
    public Map<String, Integer> sqlCounts() {
        return new LinkedHashMap<>(sqlCounts);
    }

    /** Handle for an open scope; closing restores the previous scope (or none). */
    public static final class Scope implements AutoCloseable {
        private final RequestStats stats;
        private final RequestStats previous;

        private Scope(RequestStats stats, RequestStats previous) {
            this.stats = stats;
            this.previous = previous;
        }

        public RequestStats stats() {
            return stats;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
