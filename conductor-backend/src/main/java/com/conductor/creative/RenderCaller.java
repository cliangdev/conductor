package com.conductor.creative;

import com.conductor.entity.User;

import java.util.Objects;

/**
 * Who is calling a Creative render operation: an explicit type, never a {@code null}, so that a missing
 * caller can only ever fail and never be mistaken for an authorized machine.
 *
 * <ul>
 *   <li>{@link UserCaller} - a person (JWT or user API key); the services check their membership and role.</li>
 *   <li>{@link RunCaller} - the render workflow run's own token. The controller has already verified that the
 *       run is a {@code conductor-creative-render} run of {@code projectId} dispatched for {@code creativeId};
 *       the services re-check that the operation targets exactly that project and Creative.</li>
 * </ul>
 */
public sealed interface RenderCaller permits RenderCaller.UserCaller, RenderCaller.RunCaller {

    /** The user id to record as {@code requestedBy}; null for a machine render. */
    String requestedBy();

    record UserCaller(User user) implements RenderCaller {
        public UserCaller {
            Objects.requireNonNull(user, "user");
        }

        @Override
        public String requestedBy() {
            return user.getId();
        }
    }

    record RunCaller(String runId, String projectId, String creativeId) implements RenderCaller {
        public RunCaller {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(creativeId, "creativeId");
        }

        @Override
        public String requestedBy() {
            return null;
        }
    }

    static RenderCaller of(User user) {
        return new UserCaller(user);
    }
}
