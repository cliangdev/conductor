package com.conductor.comment;

import com.conductor.entity.User;
import com.conductor.repository.UserRepository;
import com.conductor.service.EmailService;
import com.conductor.signal.AfterCommit;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Emails each @mentioned member (not the author, not agents) a link to the thread. Sent after the writing
 * transaction commits, so a comment that rolls back emails nobody; a failure to send is logged and never reaches
 * the commenter.
 */
@Component
public class CommentMentionEmailSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(CommentMentionEmailSubscriber.class);

    private final UserRepository userRepository;
    private final EmailService emailService;

    public CommentMentionEmailSubscriber(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Override
    public String name() {
        return "comment-mention-email";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.CONDUCTOR_COMMENT_MENTIONED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        Map<String, Object> p = signal.payload();
        String authorId = (String) p.get("authorId");
        List<String> recipients = new ArrayList<>(stringList(p.get("userIds")));
        recipients.removeIf(id -> id.equals(authorId));
        if (recipients.isEmpty()) {
            return;
        }
        String author = String.valueOf(p.getOrDefault("authorName", "Someone"));
        String title = String.valueOf(p.getOrDefault("documentTitle", "a document"));
        String excerpt = String.valueOf(p.getOrDefault("excerpt", ""));
        String link = String.valueOf(p.getOrDefault("link", ""));
        AfterCommit.run(() -> {
            for (String userId : recipients) {
                try {
                    User user = userRepository.findById(userId).orElse(null);
                    if (user != null) {
                        emailService.sendMentionEmail(user, author, title, excerpt, link);
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not email mention to user {}: {}", userId, e.getMessage());
                }
            }
        });
    }

    @Override
    public int order() {
        return SignalDispatchOrder.COMMENT_MENTION_EMAIL;
    }

    /** A signal payload's list of ids (the bus keeps it as an Object). */
    static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?> items) {
            for (Object item : items) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }
}
