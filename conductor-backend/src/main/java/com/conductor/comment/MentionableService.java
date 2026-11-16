package com.conductor.comment;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.repository.ProjectMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Who can be @-mentioned in a comment: the project's members and its ACTIVE, addressable agents. */
@Service
public class MentionableService {

    private static final int MAX_RESULTS = 20;

    /** {@code avatarUrl} for a person; {@code avatarEmoji}/{@code avatarColor} for an agent. */
    public record Mentionable(Mention.Kind kind, String id, String name, String avatarUrl, String avatarEmoji,
                              String avatarColor) {
    }

    private final ProjectMemberRepository projectMemberRepository;
    private final AgentRepository agentRepository;

    public MentionableService(ProjectMemberRepository projectMemberRepository, AgentRepository agentRepository) {
        this.projectMemberRepository = projectMemberRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * Members and addressable agents whose name contains {@code q} (case-insensitive; blank = everyone), people
     * first, then agents, each alphabetically, capped at {@value #MAX_RESULTS}. The caller has checked access.
     */
    @Transactional(readOnly = true)
    public List<Mentionable> search(String projectId, String q) {
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        List<Mentionable> people = new ArrayList<>();
        for (ProjectMember member : projectMemberRepository.findByProjectIdWithUser(projectId)) {
            User user = member.getUser();
            String name = personName(user);
            if (matches(name, needle)) {
                people.add(new Mentionable(Mention.Kind.USER, user.getId(), name, user.getAvatarUrl(), null, null));
            }
        }
        List<Mentionable> agents = new ArrayList<>();
        for (Agent agent : agentRepository.findByProjectId(projectId)) {
            if (!"ACTIVE".equals(agent.getState()) || !agent.isAddressable()) {
                continue;
            }
            if (matches(agent.getName(), needle) || (agent.getSlug() != null && matches(agent.getSlug(), needle))) {
                agents.add(new Mentionable(Mention.Kind.AGENT, agent.getId(), agent.getName(), null,
                        agent.getAvatarEmoji() != null ? agent.getAvatarEmoji()
                                : AgentAvatarDefaults.defaultEmoji(agent.getSlug()),
                        agent.getAvatarColor() != null ? agent.getAvatarColor()
                                : AgentAvatarDefaults.defaultColor(agent.getSlug())));
            }
        }
        Comparator<Mentionable> byName = Comparator.comparing(m -> m.name().toLowerCase(Locale.ROOT));
        people.sort(byName);
        agents.sort(byName);
        List<Mentionable> all = new ArrayList<>(people);
        all.addAll(agents);
        return all.size() > MAX_RESULTS ? all.subList(0, MAX_RESULTS) : all;
    }

    private static boolean matches(String name, String needle) {
        return needle.isEmpty() || (name != null && name.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static String personName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getEmail();
    }
}
