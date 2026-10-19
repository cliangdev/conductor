package com.conductor.service;

import com.conductor.entity.PublishConsentEvent;
import com.conductor.entity.User;
import com.conductor.repository.PublishConsentEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the append-only {@code publish_consent_event} trail (V153): who consented, withdrew or was
 * refused, through which door, and what they were shown.
 *
 * <p>Two entry points, because the two kinds of event have opposite transaction needs. A
 * <em>consent</em> event is part of the write it describes and must commit or roll back with it, or the
 * trail would claim a consent that never happened ({@link #append}). A <em>refusal</em> describes a write
 * that was rejected, and the rejection throws — which would roll the event back with it — so it commits on
 * its own ({@link #appendRefusal}).
 *
 * <p>The actor, credential type, IP and user agent are read from the current request here, so no caller
 * can forget them or pass different ones.
 */
@Component
public class PublishConsentAudit {

    private static final int USER_AGENT_MAX = 512;
    private static final int IP_MAX = 64;

    private final PublishConsentEventRepository repository;
    private final ProjectSecurityService projectSecurityService;

    public PublishConsentAudit(PublishConsentEventRepository repository,
                               ProjectSecurityService projectSecurityService) {
        this.repository = repository;
        this.projectSecurityService = projectSecurityService;
    }

    /** What was decided, on which Post, and what the person was shown. All but the ids are optional. */
    public record Entry(String action, String projectId, String workItemId, String subjectHash,
                        String declarationJson, String declarationVersion, String channel, String requestId) {}

    /** Appends in the caller's transaction. */
    @Transactional
    public void append(Entry entry) {
        repository.save(toEvent(entry));
    }

    /** Appends in a transaction of its own, so a refusal's event survives the refusal's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendRefusal(Entry entry) {
        repository.save(toEvent(entry));
    }

    private PublishConsentEvent toEvent(Entry entry) {
        PublishConsentEvent event = new PublishConsentEvent();
        event.setAction(entry.action());
        event.setProjectId(entry.projectId());
        event.setWorkItemId(entry.workItemId());
        event.setSubjectHash(entry.subjectHash());
        event.setDeclarationJson(entry.declarationJson());
        event.setDeclarationVersion(entry.declarationVersion());
        event.setChannel(entry.channel());
        event.setRequestId(entry.requestId());
        User user = projectSecurityService.currentUserOrNull();
        event.setUserId(user == null ? null : user.getId());
        event.setCredentialType(projectSecurityService.credentialType());
        HttpServletRequest request = currentRequest();
        if (request != null) {
            String ip = clientIp(request);
            event.setIp(ip == null ? null : ip.substring(0, Math.min(IP_MAX, ip.length())));
            String userAgent = request.getHeader("User-Agent");
            event.setUserAgent(userAgent == null ? null
                    : userAgent.substring(0, Math.min(USER_AGENT_MAX, userAgent.length())));
        }
        return event;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /** Behind Cloud Run the peer is the load balancer; the original client is the first forwarded hop. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
