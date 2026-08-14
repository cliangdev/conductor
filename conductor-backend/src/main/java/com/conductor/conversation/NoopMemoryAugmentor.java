package com.conductor.conversation;

import com.conductor.agent.provider.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/** Default {@link MemoryAugmentor}: no long-term memory store exists yet, so the window passes through
 *  unchanged. */
@Component
public class NoopMemoryAugmentor implements MemoryAugmentor {

    @Override
    public List<ChatMessage> augment(String projectId, String agentId, String conversationId, List<ChatMessage> window) {
        return window;
    }
}
