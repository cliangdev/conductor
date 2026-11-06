package com.conductor.marketing.aivisibility;

import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ChatResponse.StopReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** A provider that answers from a function and records every call, so no test ever reaches a real API. */
final class FakeChatModelProvider implements ChatModelProvider {

    record Call(ChatRequest request, String apiKey) {
        String question() {
            return request.messages().get(0).text();
        }
    }

    private final String id;
    private final String defaultModel;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    private Function<String, ChatResponse> answers;

    FakeChatModelProvider(String id, String defaultModel, Function<String, String> answers) {
        this.id = id;
        this.defaultModel = defaultModel;
        answeringWith(answers);
    }

    static FakeChatModelProvider answering(String id, String answer) {
        return new FakeChatModelProvider(id, id + "-default", question -> answer);
    }

    FakeChatModelProvider answeringWith(Function<String, String> function) {
        this.answers = question -> new ChatResponse(StopReason.COMPLETE, function.apply(question), List.of(), null);
        return this;
    }

    FakeChatModelProvider respondingWith(Function<String, ChatResponse> function) {
        this.answers = function;
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String defaultModel() {
        return defaultModel;
    }

    @Override
    public ChatResponse complete(ChatRequest request, String apiKey) {
        Call call = new Call(request, apiKey);
        calls.add(call);
        return answers.apply(call.question());
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    int callCount() {
        return calls.size();
    }

    void reset() {
        calls.clear();
    }
}
