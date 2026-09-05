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

    static ActionResult answer(Map<String, Object> input) {
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
                rows.add(row);
            }
        }
        return ActionResult.ok(Map.of("metrics", rows));
    }
}
