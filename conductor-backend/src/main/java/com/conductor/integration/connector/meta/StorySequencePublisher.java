package com.conductor.integration.connector.meta;

import com.conductor.integration.ActionResult;
import com.conductor.integration.connector.meta.MetaConnector.MetaActions;
import com.conductor.integration.connector.meta.MetaConnector.PublishMedia;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Publishes a story, or a story <em>sequence</em>, on Instagram or Facebook: neither platform has a
 * multi-item story, so N items are N stories posted back to back, in order.
 *
 * <h2>Resume</h2>
 * A story is live the instant its publish call returns and cannot be scheduled or recalled in bulk, so a
 * retry after a partial failure must never post the stories that already went out. After <b>each</b> item
 * succeeds, the ids published so far are written to the target row's {@code resume_checkpoint} (through
 * {@link Store}); a later attempt reads them back and starts at the first item with no id.
 *
 * <p>The checkpoint lives on {@code post_publish_target}, not on the action invocation, because the retry
 * path ({@code PublishOutcomeService#retryFailedTargets}) mints a <em>fresh</em> idempotency key — the
 * invocation row, and any checkpoint on it, belongs to the failed attempt. The target row is the one thing
 * both attempts share. It carries the media fingerprint (the ordered storage paths) too: if the author
 * changed the files between attempts, only the published stories whose file is still at the same position,
 * counting from the first, are kept — those are live and posting them again would duplicate them — and the
 * sequence resumes after that prefix. A change at the first position keeps nothing. Without a
 * {@code target_id} (an ad-hoc invocation) nothing is checkpointed.
 *
 * <h2>Cancellation</h2>
 * The invocation deadline cancels the publishing thread with an interrupt, and a target whose invocation
 * timed out is {@code FAILED} and retryable at once. Nothing on the image path ever blocks interruptibly, so
 * the loop checks the interrupt itself before each story: a cancelled sequence stops instead of racing the
 * retry for the same next story. A story that went live while the interrupt landed is still checkpointed.
 *
 * <p>The checkpoint is left in place on success: it is the complete record of every story id, of which the
 * target's {@code platform_post_id} holds only the first.
 */
final class StorySequencePublisher {

    private static final Logger log = LoggerFactory.getLogger(StorySequencePublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KIND = "story_sequence";

    /** Where the progress lives. A no-op store makes every attempt start from the first item. */
    interface Store {
        Optional<String> read(String targetId);

        void save(String targetId, String json);

        static Store none() {
            return new Store() {
                @Override
                public Optional<String> read(String targetId) {
                    return Optional.empty();
                }

                @Override
                public void save(String targetId, String json) {
                }
            };
        }
    }

    /** One story that went live. */
    record Story(String id, String permalink) {}

    /** One item's outcome: the story that went live, or the permanent failure to report instead. */
    record Item(Story story, ActionResult error) {
        static Item published(String id, String permalink) {
            return new Item(new Story(id, permalink), null);
        }

        static Item failed(ActionResult error) {
            return new Item(null, error);
        }
    }

    /** Every story is live ({@code stories}), or the sequence stopped with {@code error}. */
    record Outcome(List<Story> stories, ActionResult error) {}

    @FunctionalInterface
    interface ItemPublisher {
        /**
         * @param publishedIds the ids of the stories this sequence already put live, so a lookup of "the
         *                     newest story" (a lost publish answer) can rule them out
         */
        Item publish(int index, PublishMedia item, java.util.Set<String> publishedIds) throws InterruptedException;
    }

    private final Store store;

    StorySequencePublisher(Store store) {
        this.store = store;
    }

    /**
     * @param platformLabel   "Instagram" / "Facebook", for messages
     * @param accountLabel    the account, e.g. "@acme"; may be null
     * @param rejectionContext prefix for a 4xx from the platform, e.g. "Instagram rejected the post"
     */
    Outcome publish(String targetId, List<PublishMedia> media, String platformLabel, String accountLabel,
                         String rejectionContext, ItemPublisher publisher) throws InterruptedException {
        List<String> fingerprint = media.stream().map(m -> m.gcsPath() != null ? m.gcsPath() : "").toList();
        List<Story> published = new ArrayList<>(load(targetId, fingerprint));
        if (published.size() > media.size()) {
            published = new ArrayList<>();
        }
        int total = media.size();
        if (!published.isEmpty()) {
            log.info("Resuming {} story sequence for target {} at story {} of {}", platformLabel, targetId,
                    published.size() + 1, total);
        }

        for (int index = published.size(); index < total; index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Cancelled after " + published.size() + " of " + total
                        + " stories to " + platformLabel);
            }
            Item result;
            try {
                java.util.Set<String> publishedIds = new java.util.HashSet<>();
                published.forEach(story -> publishedIds.add(story.id()));
                result = publisher.publish(index, media.get(index), publishedIds);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    throw progressed(e, published.size(), total, platformLabel, accountLabel, e);
                }
                ActionResult rejected = MetaActions.permanentOrRethrow(e, rejectionContext);
                return fail(rejected.message(), published.size(), total, platformLabel, accountLabel);
            } catch (InterruptedException e) {
                throw e;
            } catch (RuntimeException e) {
                throw progressed(e, published.size(), total, platformLabel, accountLabel, null);
            }
            if (result.error() != null) {
                return fail(result.error().message(), published.size(), total, platformLabel, accountLabel);
            }
            published.add(result.story());
            save(targetId, fingerprint, published);
        }
        return new Outcome(published, null);
    }

    /**
     * The output keys common to both platforms; the caller adds its own id key (the first story's id, which
     * becomes the target's {@code platform_post_id}) and permalink.
     */
    static Map<String, Object> output(List<Story> published) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("is_story", true);
        if (published.size() > 1) {
            output.put("story_ids", published.stream().map(Story::id).toList());
            output.put("story_count", published.size());
        }
        return output;
    }

    private static Outcome fail(String reason, int done, int total, String platform, String account) {
        if (total <= 1) {
            return new Outcome(List.of(), ActionResult.error(reason));
        }
        return new Outcome(List.of(),
                ActionResult.error(progressMessage(done, total, platform, account) + " " + reason));
    }

    /** A transient failure keeps its transient classification; only the message learns how far it got. */
    private static RuntimeException progressed(RuntimeException cause, int done, int total, String platform,
                                               String account, Throwable chain) {
        if (total <= 1) {
            return cause;
        }
        return new IllegalStateException(progressMessage(done, total, platform, account) + " " + cause.getMessage(),
                chain);
    }

    static String progressMessage(int done, int total, String platform, String account) {
        return "Published " + done + " of " + total + " stories to " + platform
                + (account != null && !account.isBlank() ? " (" + account + ")" : "")
                + "; retrying resumes at story " + (done + 1) + ".";
    }

    // ---- checkpoint --------------------------------------------------------------------------

    private List<Story> load(String targetId, List<String> fingerprint) {
        if (targetId == null || targetId.isBlank()) {
            return List.of();
        }
        try {
            Optional<String> stored = store.read(targetId).filter(json -> !json.isBlank());
            if (stored.isEmpty()) {
                return List.of();
            }
            JsonNode node = MAPPER.readTree(stored.get());
            if (!KIND.equals(node.path("kind").asText())) {
                return List.of();
            }
            List<String> media = new ArrayList<>();
            node.path("media").forEach(n -> media.add(n.asText()));
            List<Story> stories = new ArrayList<>();
            for (JsonNode n : node.path("stories")) {
                int index = stories.size();
                if (index >= media.size() || index >= fingerprint.size()
                        || !media.get(index).equals(fingerprint.get(index))) {
                    break;
                }
                stories.add(new Story(n.path("id").asText(), n.path("permalink").asText(null)));
            }
            if (!media.equals(fingerprint)) {
                log.warn("The media on target {} changed since its story checkpoint was written; keeping the {} "
                        + "live stories whose files are unchanged at the start of the sequence", targetId,
                        stories.size());
            }
            return stories;
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Ignoring an unreadable story checkpoint on target {}: {}", targetId, e.getMessage());
            return List.of();
        }
    }

    private void save(String targetId, List<String> fingerprint, List<Story> published) {
        if (targetId == null || targetId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("kind", KIND);
            json.put("media", fingerprint);
            json.put("stories", published.stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id());
                m.put("permalink", s.permalink());
                return m;
            }).toList());
            String serialized = MAPPER.writeValueAsString(json);
            // A cancel can land while the story's publish call is in flight: the story is live, so record it.
            // The interrupt is set aside for the write (a pooled connection checkout refuses an interrupted
            // thread) and restored, so the loop still stops before the next story.
            boolean interrupted = Thread.interrupted();
            try {
                store.save(targetId, serialized);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            // Losing the checkpoint risks a duplicate story on a later retry, but failing here would turn a
            // story that is already live into a reported failure — which invites exactly that retry.
            log.error("Could not checkpoint story {} of target {}: {}", published.size(), targetId, e.getMessage());
        }
    }
}
