package com.conductor.signal;

import java.util.regex.Pattern;

/**
 * Matches a {@link Signal#type()} string against a subscription pattern of dot-separated
 * segments. Deliberately stricter than {@code KnowledgeDomainResolver}'s single-{@code *}
 * substring glob: here wildcards only ever stand for whole segments, because signal types are
 * a namespace hierarchy (see {@link SignalTypes}) and a substring match would let e.g.
 * {@code "github.pull_request*"} accidentally also match {@code "github.pull_request_merged"} --
 * exactly the collision {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED}'s flat naming is meant to
 * prevent. Segment-bounded matching closes that hole regardless of naming discipline.
 *
 * <p>Grammar, matched one dot-separated segment at a time:
 * <ul>
 *   <li>{@code *} as a whole segment matches exactly one segment.</li>
 *   <li>{@code **} as a whole segment matches one or more segments.</li>
 *   <li>any other segment is matched literally (regex-quoted).</li>
 *   <li>a pattern with no {@code *} anywhere is exact string equality.</li>
 * </ul>
 */
public final class SignalGlob {

    private SignalGlob() {
    }

    public static boolean matches(String pattern, String signalType) {
        if (pattern.indexOf('*') < 0) {
            return pattern.equals(signalType);
        }
        return Pattern.matches(toRegex(pattern), signalType);
    }

    private static String toRegex(String pattern) {
        String[] segments = pattern.split("\\.", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                regex.append("\\.");
            }
            String segment = segments[i];
            if (segment.equals("**")) {
                regex.append("[^.]+(?:\\.[^.]+)*");
            } else if (segment.equals("*")) {
                regex.append("[^.]+");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        return regex.toString();
    }
}
