package com.conductor.conversation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool for {@link AgentConversationRunner#submit}. Deliberately small and deliberately strict --
 * an {@link ThreadPoolExecutor.AbortPolicy} rejection, not the caller-runs policy {@code
 * IntegrationConfig}'s pools use -- so a rejected submission becomes an honest "I'm busy, try again"
 * reply to the human on the other end of the conversation, rather than a request silently piling up
 * behind a long queue. This is deliberately NOT a {@code @Scheduled} bean; nothing polls it.
 *
 * <p>The durable-queue upgrade path, if this in-memory pool proves too blunt, mirrors the workflow job
 * queue: persist the pending turn and let a scheduler pick it up ({@code workflow_job_runs} +
 * {@code WorkflowExecutionEngine} is the precedent), rather than holding it in an ephemeral in-JVM
 * queue that a restart would drop.
 */
@Configuration
public class ConversationExecutorConfig {

    @Bean(name = "conversationExecutor")
    public ExecutorService conversationExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "conversation-runner-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
