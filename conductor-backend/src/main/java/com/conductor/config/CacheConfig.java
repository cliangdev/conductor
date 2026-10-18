package com.conductor.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * In-process Caffeine caches for hot, read-mostly lookups. Every cache is size-bounded and time-limited so
 * a stale entry is never served for longer than its TTL (instances do not share caches).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Parsed lifecycle {@code Statechart}s for a pinned (projectId, slug, version): immutable, so long-lived. */
    public static final String STATECHART_PINNED = "statechartPinned";
    /** Latest PUBLISHED Statechart per (projectId, slug); short TTL, evicted on publish/update/delete. */
    public static final String STATECHART_LATEST = "statechartLatest";
    /** Authenticated user per id for the JWT filter; short TTL, evicted on profile edits. */
    public static final String AUTH_USERS = "authUsers";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                build(STATECHART_PINNED, 2000, Duration.ofHours(6)),
                build(STATECHART_LATEST, 1000, Duration.ofSeconds(30)),
                build(AUTH_USERS, 5000, Duration.ofSeconds(60))));
        manager.afterPropertiesSet();
        return manager;
    }

    private static CaffeineCache build(String name, long maxSize, Duration ttl) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build());
    }
}
