package com.conductor.comment;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.repository.ProjectMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MentionParserTest {

    static final String PROJECT = "p1";

    ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    AgentRepository agents = mock(AgentRepository.class);
    MentionParser parser = new MentionParser(members, agents);

    User ann = user("u-ann", "Ann Lee");
    Agent active = agent("a-1", "Editor Bot", "editor-bot", "ACTIVE", true);
    Agent draft = agent("a-2", "Draft Bot", "draft-bot", "DRAFT", true);
    Agent quiet = agent("a-3", "Quiet Bot", "quiet-bot", "ACTIVE", false);

    @BeforeEach
    void setUp() {
        when(members.findByProjectIdAndUserIdIn(eq(PROJECT), any())).thenAnswer(inv -> {
            Collection<String> ids = inv.getArgument(1);
            return ids.contains("u-ann") ? List.of(member(ann)) : List.of();
        });
        when(agents.findByProjectId(PROJECT)).thenReturn(List.of(active, draft, quiet));
    }

    @Test
    void aMemberMentionIsKeptWithTheCanonicalNameAndId() {
        var parsed = parser.parse(PROJECT, "hi @[Anything I typed](user:u-ann), thanks", true);

        assertThat(parsed.content()).isEqualTo("hi @[Ann Lee](user:u-ann), thanks");
        assertThat(parsed.mentions()).containsExactly(new Mention(Mention.Kind.USER, "u-ann", "Ann Lee"));
    }

    @Test
    void aForgedOrNonMemberUserIdBecomesPlainText() {
        var parsed = parser.parse(PROJECT, "@[Mallory](user:u-mallory) and @[Root](user:u.root)", true);

        assertThat(parsed.content()).isEqualTo("@Mallory and @Root");
        assertThat(parsed.mentions()).isEmpty();
    }

    @Test
    void anActiveAddressableAgentIsKeptAndASlugIsRewrittenToItsId() {
        var byId = parser.parse(PROJECT, "@[x](agent:a-1)", true);
        var bySlug = parser.parse(PROJECT, "@[x](agent:Editor-Bot)", true);

        assertThat(byId.content()).isEqualTo("@[Editor Bot](agent:a-1)");
        assertThat(bySlug.content()).isEqualTo("@[Editor Bot](agent:a-1)");
        assertThat(bySlug.mentions()).containsExactly(new Mention(Mention.Kind.AGENT, "a-1", "Editor Bot"));
    }

    @Test
    void anInactiveOrNonAddressableOrUnknownAgentBecomesPlainText() {
        var parsed = parser.parse(PROJECT, "@[Draft Bot](agent:a-2) @[Quiet Bot](agent:a-3) @[Ghost](agent:nope)", true);

        assertThat(parsed.content()).isEqualTo("@Draft Bot @Quiet Bot @Ghost");
        assertThat(parsed.mentions()).isEmpty();
    }

    @Test
    void machineWrittenTextCannotMentionAgentsButCanMentionPeople() {
        var parsed = parser.parse(PROJECT, "@[Editor Bot](agent:a-1) cc @[Ann](user:u-ann)", false);

        assertThat(parsed.content()).isEqualTo("@Editor Bot cc @[Ann Lee](user:u-ann)");
        assertThat(parsed.mentions()).extracting(Mention::kind).containsExactly(Mention.Kind.USER);
        verify(agents, never()).findByProjectId(any());
    }

    @Test
    void theSameMentionTwiceIsOneMention() {
        var parsed = parser.parse(PROJECT, "@[a](user:u-ann) @[b](user:u-ann)", true);

        assertThat(parsed.mentions()).hasSize(1);
    }

    @Test
    void textWithoutTokensIsUntouchedAndCostsNoLookups() {
        var parsed = parser.parse(PROJECT, "email me @ann or [link](user:u-ann)", true);

        assertThat(parsed.content()).isEqualTo("email me @ann or [link](user:u-ann)");
        assertThat(parsed.mentions()).isEmpty();
        verify(members, never()).findByProjectIdAndUserIdIn(any(), any());
    }

    @Test
    void aNameWithBracketsOrNewlinesCannotBreakOutOfTheToken() {
        User sneaky = user("u-ann", "Ann](agent:a-1) [x\nLee");
        when(members.findByProjectIdAndUserIdIn(eq(PROJECT), any())).thenReturn(List.of(member(sneaky)));

        var parsed = parser.parse(PROJECT, "@[a](user:u-ann)", true);

        assertThat(parsed.content()).doesNotContain("\n");
        assertThat(MentionParser.extract(parsed.content())).hasSize(1)
                .allMatch(m -> m.kind() == Mention.Kind.USER);
    }

    @Test
    void extractReadsAStoredBodyWithoutLookups() {
        assertThat(MentionParser.extract("a @[Ann Lee](user:u-ann) b @[Editor Bot](agent:a-1)"))
                .containsExactly(new Mention(Mention.Kind.USER, "u-ann", "Ann Lee"),
                        new Mention(Mention.Kind.AGENT, "a-1", "Editor Bot"));
        assertThat(MentionParser.extract(null)).isEmpty();
    }

    @Test
    void addedReturnsOnlyMentionsNewInAnEdit() {
        String before = "@[Ann Lee](user:u-ann) look";
        String after = "@[Ann Lee](user:u-ann) look, and @[Editor Bot](agent:a-1)";

        assertThat(MentionParser.added(before, after)).extracting(Mention::id).containsExactly("a-1");
        assertThat(MentionParser.added(after, before)).isEmpty();
        assertThat(MentionParser.added(before, before)).isEmpty();
        assertThat(MentionParser.added(null, before)).extracting(Mention::id).containsExactly("u-ann");
    }

    @Test
    void plainTextShowsEachTokenAsItsName() {
        assertThat(MentionParser.toPlainText("hi @[Ann Lee](user:u-ann)!")).isEqualTo("hi @Ann Lee!");
        assertThat(MentionParser.toPlainText("no tokens")).isEqualTo("no tokens");
        assertThat(MentionParser.toPlainText(null)).isNull();
    }

    private static User user(String id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setEmail(id + "@x.test");
        return u;
    }

    private static ProjectMember member(User u) {
        ProjectMember m = new ProjectMember();
        m.setUser(u);
        return m;
    }

    private static Agent agent(String id, String name, String slug, String state, boolean addressable) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId(PROJECT);
        a.setName(name);
        a.setSlug(slug);
        a.setState(state);
        a.setConfigJson("{\"addressable\": " + addressable + "}");
        return a;
    }
}
