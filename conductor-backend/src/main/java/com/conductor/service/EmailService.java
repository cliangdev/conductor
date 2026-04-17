package com.conductor.service;

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

    public void sendOrgInviteEmail(String toEmail, String inviterName, String orgName) {
        String apiKey = System.getenv("RESEND_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("RESEND_API_KEY not set — skipping org invite email to {}", toEmail);
            return;
        }

        String subject = inviterName + " invited you to " + orgName + " on Conductor";
        String body = "<html><body>" +
                "<p>Hi,</p>" +
                "<p><strong>" + inviterName + "</strong> has invited you to join <strong>" + orgName + "</strong> on Conductor.</p>" +
                "<p>Sign in to Conductor to get started.</p>" +
                "</body></html>";

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
            log.error("Failed to send org invite email to {}: {}", toEmail, e.getMessage());
        }
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
