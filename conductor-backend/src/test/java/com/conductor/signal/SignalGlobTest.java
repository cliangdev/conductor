package com.conductor.signal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SignalGlobTest {

    @ParameterizedTest(name = "pattern=''{0}'' type=''{1}'' -> {2}")
    @MethodSource("cases")
    void matches(String pattern, String signalType, boolean expected) {
        assertThat(SignalGlob.matches(pattern, signalType)).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // a single '*' segment does not swallow the rest of a longer literal segment name
                Arguments.of("github.pull_request.*", "github.pull_request", false),
                // the flat merged-PR type must never be caught by a prefix/glob match against the base type
                Arguments.of("github.pull_request", "github.pull_request_merged", false),
                // exact equality, no wildcard at all
                Arguments.of("github.pull_request", "github.pull_request", true),
                Arguments.of("github.pull_request_merged", "github.pull_request_merged", true),
                // '**' matches one or more trailing segments
                Arguments.of("conductor.**", "conductor.work_item.status_changed", true),
                Arguments.of("conductor.**", "conductor.workflow.auto_paused", true),
                // a single '*' matches exactly one segment, not two
                Arguments.of("conductor.*", "conductor.work_item.status_changed", false),
                Arguments.of("conductor.*", "conductor.workflow", true),
                // '**' alone matches anything
                Arguments.of("**", "github.pull_request", true),
                Arguments.of("**", "conductor.work_item.status_changed", true),
                Arguments.of("**", "x", true),
                // a middle '*' segment matches exactly one segment in that position
                Arguments.of("conductor.*.status_changed", "conductor.work_item.status_changed", true),
                Arguments.of("conductor.*.status_changed", "conductor.work_item.extra.status_changed", false),
                // no match across an unrelated type
                Arguments.of("conductor.work_item.status_changed", "conductor.workflow.auto_paused", false)
        );
    }
}
