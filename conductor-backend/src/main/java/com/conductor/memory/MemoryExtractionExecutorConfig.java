package com.conductor.memory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool for {@link MemoryExtractionService}'s post-turn extraction jobs. Deliberately NOT the
 * {@code conversationExecutor} ({@code ConversationExecutorConfig}) — extraction is best-effort
 * background work that must never compete with live conversation turns for threads. Also deliberately
 * NOT that pool's {@code AbortPolicy}: no human is waiting on an extraction the way a conversation
 * turn's caller is, so a full queue silently drops the oldest queued (not yet started) extraction job
 * via {@link ThreadPoolExecutor.DiscardOldestPolicy} rather than rejecting (and having to be handled
 * by) the caller. Small on purpose — 1-2 threads is plenty for a fire-and-forget LLM call per turn.
 */
@Configuration
public class MemoryExtractionExecutorConfig {

    @Bean(name = "memoryExtractionExecutor")
    public ExecutorService memoryExtractionExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                1, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "memory-extraction-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }
}
