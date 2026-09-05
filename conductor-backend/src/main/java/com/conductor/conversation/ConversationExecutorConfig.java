package com.conductor.conversation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two identically-sized, identically-strict bounded pools -- one per conversation surface -- so a burst
 * on one can never starve the other: {@code restConversationExecutor} backs {@link
 * AgentConversationRunner#submit} (the REST {@code POST .../messages} path), and {@code
 * discordConversationExecutor} backs {@code DiscordAppConnector#handleEvent}'s enqueue of the entire
 * {@code /ask} flow. Before this split, both surfaces shared one pool: a burst of Discord traffic could
 * exhaust every thread and queue slot, and an unrelated REST caller would get a rejection with nothing
 * to do with Discord, and vice versa. Splitting the pool -- not enlarging it -- is the fix; each half is
 * exactly as small and exactly as strict as the single pool used to be.
 *
 * <p>Each pool is deliberately small and deliberately strict -- an {@link ThreadPoolExecutor.AbortPolicy}
 * rejection, not the caller-runs policy {@code IntegrationConfig}'s pools use -- so a rejected submission
 * becomes an honest "I'm busy, try again" reply to the human on the other end of the conversation, rather
 * than a request silently piling up behind a long queue. Neither is a {@code @Scheduled} bean; nothing
 * polls them.
 *
 * <p>The durable-queue upgrade path, if either in-memory pool proves too blunt, mirrors the workflow job
 * queue: persist the pending turn and let a scheduler pick it up ({@code workflow_job_runs} +
 * {@code WorkflowExecutionEngine} is the precedent), rather than holding it in an ephemeral in-JVM
 * queue that a restart would drop.
 */
@Configuration
public class ConversationExecutorConfig {

    @Bean(name = "restConversationExecutor")
    public ExecutorService restConversationExecutor() {
        return boundedPool("rest-conversation-runner-");
    }

    @Bean(name = "discordConversationExecutor")
    public ExecutorService discordConversationExecutor() {
        return boundedPool("discord-conversation-runner-");
    }

    private ExecutorService boundedPool(String threadNamePrefix) {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                2, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, threadNamePrefix + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
