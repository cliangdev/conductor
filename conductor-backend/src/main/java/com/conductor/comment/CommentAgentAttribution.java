package com.conductor.comment;

import com.conductor.agent.AgentRepository;
import com.conductor.security.WorkflowRunAuthenticationToken;
import com.conductor.service.ProjectActor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Names the agent behind a comment write made with a run-scoped token. A run token proves "this workflow run",
 * which {@code ProjectSecurityService} turns into the anonymous actor {@code Agent (run a1b2c3d4)}; when that run
 * is a comment-agent run (it has a {@code comment_agent_requests} row), the actor becomes the agent that was
 * asked, so its replies carry {@code author_agent_id}, its name and its avatar. Any other machine actor is left
 * as it was.
 */
@Component
public class CommentAgentAttribution {

    private final CommentAgentRequestService requestService;
    private final AgentRepository agentRepository;

    public CommentAgentAttribution(CommentAgentRequestService requestService, AgentRepository agentRepository) {
        this.requestService = requestService;
        this.agentRepository = agentRepository;
    }

    public ProjectActor attribute(ProjectActor actor) {
        if (!actor.isMachine() || actor.agentId() != null) {
            return actor;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof WorkflowRunAuthenticationToken run && run.getRunId() != null) {
            return requestService.agentIdForRun(run.getRunId())
                    .flatMap(agentRepository::findById)
                    .map(agent -> ProjectActor.agent(agent.getName(), agent.getId()))
                    .orElse(actor);
        }
        return actor;
    }
}
