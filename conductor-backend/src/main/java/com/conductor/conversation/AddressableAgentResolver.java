package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a human-typed agent reference (a Discord {@code @mention} target, an API {@code agent}
 * field, or nothing at all) to exactly one addressable {@link Agent} in a project. "Addressable" means
 * {@code state = ACTIVE} AND {@link Agent#isAddressable()} -- an agent must opt in via
 * {@code configJson.addressable} before a human can talk to it directly; a workflow-only agent stays
 * unreachable here even if ACTIVE.
 */
@Component
public class AddressableAgentResolver {

    private final AgentRepository agentRepository;

    public AddressableAgentResolver(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    /**
     * @param nameOrNull a slug, a display name, or blank/null for the default -- the
     *                    {@value DefaultAgentSlugs#CEO} slug (which may not exist yet; that's just
     *                    another {@link AgentNotAddressableException#notFound} case, not a special one).
     *                    Matching is case-insensitive, slug first (unique per project, so at most one
     *                    match), then display name. A name matching several agents resolves to whichever
     *                    one's slug exactly equals the attempted name; if none does, it's ambiguous.
     */
    public Agent resolve(String projectId, String nameOrNull) {
        String attempted = (nameOrNull == null || nameOrNull.isBlank())
                ? DefaultAgentSlugs.CEO : nameOrNull.trim();

        List<Agent> addressable = agentRepository.findByProjectId(projectId).stream()
                .filter(a -> "ACTIVE".equals(a.getState()) && a.isAddressable())
                .toList();

        Optional<Agent> slugMatch = addressable.stream()
                .filter(a -> a.getSlug().equalsIgnoreCase(attempted))
                .findFirst();
        if (slugMatch.isPresent()) {
            return slugMatch.get();
        }

        List<Agent> nameMatches = addressable.stream()
                .filter(a -> a.getName() != null && a.getName().equalsIgnoreCase(attempted))
                .toList();
        if (nameMatches.isEmpty()) {
            throw AgentNotAddressableException.notFound(attempted);
        }
        if (nameMatches.size() == 1) {
            return nameMatches.get(0);
        }
        return nameMatches.stream()
                .filter(a -> a.getSlug().equals(attempted))
                .findFirst()
                .orElseThrow(() -> AgentNotAddressableException.ambiguous(
                        attempted, nameMatches.stream().map(Agent::getSlug).toList()));
    }
}
