package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link AddressableAgentResolver}. */
class AddressableAgentResolverTest {

    private Agent agent(String id, String slug, String name, String state, boolean addressable) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId("p1");
        a.setSlug(slug);
        a.setName(name);
        a.setProvider("fake");
        a.setState(state);
        a.setConfigJson(addressable ? "{\"addressable\":true}" : "{}");
        return a;
    }

    private AddressableAgentResolver resolverWith(Agent... agents) {
        AgentRepository repo = mock(AgentRepository.class);
        when(repo.findByProjectId("p1")).thenReturn(List.of(agents));
        return new AddressableAgentResolver(repo, mock(CoordinatorProvisioner.class));
    }

    @Test
    void blankNameDefaultsToTheCeoSlug() {
        Agent ceo = agent("a1", DefaultAgentSlugs.CEO, "Cee Oh", "ACTIVE", true);
        AddressableAgentResolver resolver = resolverWith(ceo);

        assertThat(resolver.resolve("p1", null).getId()).isEqualTo("a1");
        assertThat(resolver.resolve("p1", "").getId()).isEqualTo("a1");
        assertThat(resolver.resolve("p1", "   ").getId()).isEqualTo("a1");
    }

    @Test
    void slugMatchIsCaseInsensitive() {
        Agent agent = agent("a1", "metrics-analyst", "Metrics Analyst", "ACTIVE", true);
        AddressableAgentResolver resolver = resolverWith(agent);

        assertThat(resolver.resolve("p1", "METRICS-ANALYST").getId()).isEqualTo("a1");
    }

    @Test
    void nameMatchIsCaseInsensitiveWhenNoSlugMatches() {
        Agent agent = agent("a1", "metrics-analyst", "Metrics Analyst", "ACTIVE", true);
        AddressableAgentResolver resolver = resolverWith(agent);

        assertThat(resolver.resolve("p1", "metrics analyst").getId()).isEqualTo("a1");
    }

    @Test
    void nonAddressableAgentsAreExcludedEvenWithAMatchingSlug() {
        Agent agent = agent("a1", "metrics-analyst", "Metrics Analyst", "ACTIVE", false);
        AddressableAgentResolver resolver = resolverWith(agent);

        assertThatThrownBy(() -> resolver.resolve("p1", "metrics-analyst"))
                .isInstanceOf(AgentNotAddressableException.class);
    }

    @Test
    void draftAgentsAreExcludedEvenIfAddressable() {
        Agent agent = agent("a1", "metrics-analyst", "Metrics Analyst", "DRAFT", true);
        AddressableAgentResolver resolver = resolverWith(agent);

        assertThatThrownBy(() -> resolver.resolve("p1", "metrics-analyst"))
                .isInstanceOf(AgentNotAddressableException.class);
    }

    @Test
    void noMatchThrowsNotFoundCarryingTheAttemptedName() {
        AddressableAgentResolver resolver = resolverWith(agent("a1", "ceo", "CEO", "ACTIVE", true));

        assertThatThrownBy(() -> resolver.resolve("p1", "nonexistent"))
                .isInstanceOf(AgentNotAddressableException.class)
                .satisfies(e -> assertThat(((AgentNotAddressableException) e).attemptedName()).isEqualTo("nonexistent"));
    }

    @Test
    void missingDefaultCeoThrowsNotFound() {
        AddressableAgentResolver resolver = resolverWith(); // no agents at all

        assertThatThrownBy(() -> resolver.resolve("p1", null))
                .isInstanceOf(AgentNotAddressableException.class)
                .satisfies(e -> assertThat(((AgentNotAddressableException) e).attemptedName())
                        .isEqualTo(DefaultAgentSlugs.CEO));
    }

    @Test
    void ambiguousNameMatchIsResolvedByExactSlugTiebreak() {
        Agent first = agent("a1", "assistant-1", "Assistant", "ACTIVE", true);
        Agent second = agent("a2", "Assistant", "Assistant", "ACTIVE", true); // slug exactly matches attempted name
        AddressableAgentResolver resolver = resolverWith(first, second);

        assertThat(resolver.resolve("p1", "Assistant").getId()).isEqualTo("a2");
    }

    @Test
    void ambiguousNameMatchWithNoExactSlugTiebreakThrowsAmbiguous() {
        Agent first = agent("a1", "assistant-1", "Assistant", "ACTIVE", true);
        Agent second = agent("a2", "assistant-2", "Assistant", "ACTIVE", true);
        AddressableAgentResolver resolver = resolverWith(first, second);

        assertThatThrownBy(() -> resolver.resolve("p1", "Assistant"))
                .isInstanceOf(AgentNotAddressableException.class)
                .hasMessageContaining("multiple");
    }
}
