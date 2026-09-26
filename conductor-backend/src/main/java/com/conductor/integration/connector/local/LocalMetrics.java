package com.conductor.integration.connector.local;

import com.conductor.integration.ActionResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stub connectors' answer to a {@code post_metrics} read: deterministic per post id and rising with
 * the clock, so a laptop's metrics view shows numbers that differ between posts and grow between pulls
 * without any of it being real. A post id ending in {@code -gone} is reported unavailable, so the
 * "platform no longer has it" path can be exercised locally too.
 */
final class LocalMetrics {

    private LocalMetrics() {
    }

    /** Meta's stub: views/likes/comments/shares/reach/saves, no watch time — Instagram/Facebook have none. */
    static ActionResult answer(Map<String, Object> input) {
        return answer(input, false);
    }

    /**
     * @param includeRetention whether to also fabricate watch time and view-percentage/duration —
     *                         YouTube's stub passes true so its insights view has retention numbers too;
     *                         Meta's has no such metric, so its stub omits them.
     */
    static ActionResult answer(Map<String, Object> input, boolean includeRetention) {
        Object raw = input.get("post_ids");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw instanceof Collection<?> ids) {
            long tick = System.currentTimeMillis() / 60_000L / 10L;
            for (Object id : ids) {
                if (id == null) {
                    continue;
                }
                String postId = String.valueOf(id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("post_id", postId);
                if (postId.endsWith("-gone")) {
                    row.put("unavailable", true);
                    rows.add(row);
                    continue;
                }
                long base = Math.floorMod(postId.hashCode(), 500);
                row.put("unavailable", false);
                row.put("views", base * 20 + tick);
                row.put("likes", base * 2 + tick / 10);
                row.put("comments", base / 5 + tick / 100);
                row.put("shares", base / 10 + tick / 200);
                row.put("reach", base * 15 + tick / 2);
                row.put("saves", base / 8 + tick / 150);
                if (includeRetention) {
                    row.put("watch_time_seconds", base * 30 + tick * 5);
                    row.put("avg_view_pct", 35.0 + (base % 40));
                    row.put("avg_view_duration_s", 12.0 + (base % 20));
                }
                rows.add(row);
            }
        }
        return ActionResult.ok(Map.of("metrics", rows));
    }
}
