package com.conductor.service;

import com.conductor.entity.User;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    public void sendInviteEmail(String toEmail, String inviterName, String projectName, String token) {
        String apiKey = System.getenv("RESEND_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("RESEND_API_KEY not set — skipping invite email to {}", toEmail);
            return;
        }

        String baseUrl = System.getenv("CONDUCTOR_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:3000";
        }

        String acceptLink = baseUrl + "/invites/" + token + "/accept";
        String subject = inviterName + " invited you to " + projectName + " on Conductor";
        String body = buildEmailBody(inviterName, projectName, acceptLink);

        try {
            Resend resend = new Resend(apiKey);
            CreateEmailOptions options = CreateEmailOptions.builder()
                    .from("noreply@conductor.app")
                    .to(toEmail)
                    .subject(subject)
                    .html(body)
                    .build();
            resend.emails().send(options);
        } catch (ResendException e) {
            log.error("Failed to send invite email to {}: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Tells a member they were @mentioned in a comment: who, where, a short excerpt and a link straight to the
     * thread. Skipped (logged) when Resend is not configured or the member has no email. Never throws: a failed
     * email must not undo or fail the comment it is about.
     */
    public void sendMentionEmail(User recipient, String authorName, String documentTitle, String excerpt,
                                 String link) {
        if (recipient == null || recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            return;
        }
        String subject = authorName + " mentioned you on " + documentTitle;
        String body = "<html><body>"
                + "<p><strong>" + escape(authorName) + "</strong> mentioned you on <strong>" + escape(documentTitle)
                + "</strong>.</p>"
                + (excerpt == null || excerpt.isBlank() ? "" : "<blockquote>" + escape(excerpt) + "</blockquote>")
                + "<p><a href=\"" + escape(link) + "\">Open the thread</a></p>"
                + "</body></html>";
        deliver(recipient.getEmail(), subject, body);
    }

    /** Sends one email through Resend, or logs and skips when {@code RESEND_API_KEY} is not set. */
    protected void deliver(String toEmail, String subject, String html) {
        String apiKey = System.getenv("RESEND_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("RESEND_API_KEY not set — skipping email to {}", toEmail);
            return;
        }
        try {
            Resend resend = new Resend(apiKey);
            resend.emails().send(CreateEmailOptions.builder()
                    .from("noreply@conductor.app")
                    .to(toEmail)
                    .subject(subject)
                    .html(html)
                    .build());
        } catch (ResendException | RuntimeException e) {
            log.error("Failed to send email to {}: {}", toEmail, e.getMessage());
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String buildEmailBody(String inviterName, String projectName, String acceptLink) {
        return "<html><body>" +
                "<p>Hi,</p>" +
                "<p><strong>" + inviterName + "</strong> has invited you to collaborate on <strong>" + projectName + "</strong> on Conductor.</p>" +
                "<p><a href=\"" + acceptLink + "\">Accept Invite</a></p>" +
                "<p>Or copy this link: " + acceptLink + "</p>" +
                "<p>This invite expires in 72 hours.</p>" +
                "</body></html>";
    }
}
