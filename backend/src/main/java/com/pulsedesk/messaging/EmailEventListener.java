package com.pulsedesk.messaging;

import com.pulsedesk.invitation.InvitationCreatedEvent;
import com.pulsedesk.notification.NotificationsCreatedEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Turns committed domain events into queued emails. Runs after commit so rolled-back changes never email. */
@Component
public class EmailEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmailEventListener.class);

    private final EmailDispatcher dispatcher;
    private final NamedParameterJdbcTemplate jdbc;
    private final MessagingProperties props;

    public EmailEventListener(EmailDispatcher dispatcher, NamedParameterJdbcTemplate jdbc,
                              MessagingProperties props) {
        this.dispatcher = dispatcher;
        this.jdbc = jdbc;
        this.props = props;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotifications(NotificationsCreatedEvent event) {
        if (!props.enabled()) {
            return;
        }
        try {
            List<String> emails = jdbc.queryForList("""
                    SELECT u.email FROM organization_members m JOIN users u ON u.id = m.user_id
                    WHERE m.id IN (:ids) AND m.organization_id = :org AND m.active AND u.active
                      AND u.email IS NOT NULL""",
                    new MapSqlParameterSource("ids", event.recipientIds()).addValue("org", event.organizationId()),
                    String.class);
            String body = (event.message() == null ? "" : event.message()) + "\n\nOpen PulseDesk to review.";
            emails.forEach(to -> dispatcher.dispatch(new EmailMessage(to, "[PulseDesk] " + event.title(), body)));
        } catch (RuntimeException e) {
            log.warn("Could not prepare notification emails: {}", e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvitation(InvitationCreatedEvent event) {
        String link = props.inviteBaseUrl() + (props.inviteBaseUrl().contains("?") ? "&" : "?") + "token="
                + event.token();
        String body = "Hi " + event.fullName() + ",\n\nYou have been invited to join " + event.organizationName()
                + " on PulseDesk as " + event.role() + ".\n\nAccept your invitation: " + link
                + "\n\nThis invitation expires at " + event.expiresAt() + " and can be used once.";
        dispatcher.dispatch(new EmailMessage(event.email(),
                "You're invited to " + event.organizationName() + " on PulseDesk", body));
    }
}
