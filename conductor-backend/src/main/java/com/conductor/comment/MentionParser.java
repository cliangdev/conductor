package com.conductor.comment;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.repository.ProjectMemberRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mentions live in the comment body as tokens: {@code @[Display Name](user:<userId>)} for a project member and
 * {@code @[Display Name](agent:<agentId>)} for an addressable agent (an agent's slug is accepted on input and
 * rewritten to its id). The client composes the token; the server never trusts it: on every write the tokens
 * are re-checked against the project and rewritten with the canonical id and name, and a token that does not
 * check out (not a member, an agent that is inactive or not addressable, an id from another project, an agent
 * mention in machine-written text) is replaced by the plain text {@code @Name}. What is stored is what was
 * validated, so reading mentions back ({@link #extract}) needs no lookup. See {@code docs/comments.md}.
 */
@Component
public class MentionParser {

    /** A mention token; the name has no brackets or newlines, ids are uuid- or slug-shaped. */
    static final Pattern TOKEN = Pattern.compile("@\\[([^\\]\\[\\n]{1,80})]\\((user|agent):([A-Za-z0-9._-]{1,64})\\)");

    static final int MAX_NAME = 80;

    /** The body to store (invalid tokens flattened to {@code @Name}) and the mentions that survived. */
    public record Parsed(String content, List<Mention> mentions) {
    }

    private final ProjectMemberRepository projectMemberRepository;
    private final AgentRepository agentRepository;

    public MentionParser(ProjectMemberRepository projectMemberRepository, AgentRepository agentRepository) {
        this.projectMemberRepository = projectMemberRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * Validates and normalises the mention tokens in {@code content}.
     *
     * @param allowAgents false for text a machine wrote: an agent can mention people, but never another agent
     *                    (the loop guard in {@code CommentAgentDispatchSubscriber} is the second line of defence)
     */
    public Parsed parse(String projectId, String content, boolean allowAgents) {
        if (content == null || content.indexOf("@[") < 0) {
            return new Parsed(content, List.of());
        }
        Set<String> userIds = new LinkedHashSet<>();
        boolean anyAgent = false;
        Matcher scan = TOKEN.matcher(content);
        while (scan.find()) {
            if ("user".equals(scan.group(2))) {
                userIds.add(scan.group(3));
            } else {
                anyAgent = true;
            }
        }

        Map<String, User> members = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (ProjectMember m : projectMemberRepository.findByProjectIdAndUserIdIn(projectId, userIds)) {
                members.put(m.getUser().getId(), m.getUser());
            }
        }
        List<Agent> agents = anyAgent && allowAgents ? agentRepository.findByProjectId(projectId) : List.of();

        Map<String, Mention> found = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(content);
        while (m.find()) {
            String typedName = m.group(1);
            String kind = m.group(2);
            String ref = m.group(3);
            Mention mention = null;
            if ("user".equals(kind)) {
                User user = members.get(ref);
                if (user != null) {
                    mention = new Mention(Mention.Kind.USER, user.getId(), cleanName(userName(user)));
                }
            } else if (allowAgents) {
                Agent agent = agents.stream()
                        .filter(a -> a.getId().equals(ref) || (a.getSlug() != null && a.getSlug().equalsIgnoreCase(ref)))
                        .findFirst().orElse(null);
                if (agent != null && "ACTIVE".equals(agent.getState()) && agent.isAddressable()) {
                    mention = new Mention(Mention.Kind.AGENT, agent.getId(), cleanName(agent.getName()));
                }
            }
            if (mention == null) {
                m.appendReplacement(out, Matcher.quoteReplacement("@" + typedName));
            } else {
                found.putIfAbsent(mention.kind() + ":" + mention.id(), mention);
                m.appendReplacement(out, Matcher.quoteReplacement(token(mention)));
            }
        }
        m.appendTail(out);
        return new Parsed(out.toString(), new ArrayList<>(found.values()));
    }

    /** The mentions in an already-validated body (the stored one); no lookups, nothing is checked. */
    public static List<Mention> extract(String content) {
        if (content == null || content.indexOf("@[") < 0) {
            return List.of();
        }
        Map<String, Mention> found = new LinkedHashMap<>();
        Matcher m = TOKEN.matcher(content);
        while (m.find()) {
            Mention.Kind kind = "user".equals(m.group(2)) ? Mention.Kind.USER : Mention.Kind.AGENT;
            found.putIfAbsent(kind + ":" + m.group(3), new Mention(kind, m.group(3), m.group(1)));
        }
        return new ArrayList<>(found.values());
    }

    /** Mentions in {@code after} that were not already in {@code before}: the ones to notify on an edit. */
    public static List<Mention> added(String before, String after) {
        Set<String> had = new LinkedHashSet<>();
        for (Mention m : extract(before)) {
            had.add(m.kind() + ":" + m.id());
        }
        return extract(after).stream().filter(m -> !had.contains(m.kind() + ":" + m.id())).toList();
    }

    /** The body with each token shown as {@code @Name}, for an excerpt, a chat message or an email. */
    public static String toPlainText(String content) {
        if (content == null || content.indexOf("@[") < 0) {
            return content;
        }
        return TOKEN.matcher(content).replaceAll(r -> Matcher.quoteReplacement("@" + r.group(1)));
    }

    static String token(Mention mention) {
        return "@[" + mention.name() + "](" + (mention.kind() == Mention.Kind.USER ? "user" : "agent") + ":"
                + mention.id() + ")";
    }

    private static String userName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getEmail() != null ? user.getEmail() : "member";
    }

    /** A name that can sit inside {@code @[...]}: no brackets or line breaks, bounded length. */
    static String cleanName(String name) {
        String cleaned = (name == null ? "" : name).replaceAll("[\\[\\]\\r\\n]+", " ").strip();
        if (cleaned.isEmpty()) {
            cleaned = "mention";
        }
        return cleaned.length() > MAX_NAME ? cleaned.substring(0, MAX_NAME) : cleaned;
    }
}
