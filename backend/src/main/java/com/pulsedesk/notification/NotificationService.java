package com.pulsedesk.notification;

import com.pulsedesk.common.exception.ApiException;
import com.pulsedesk.common.security.AuthenticatedUser;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    public record NotificationView(UUID id, UUID incidentId, String type, String title, String message,
                                   Instant createdAt, boolean read) {
    }

    private final NotificationRepository notifications;
    private final ApplicationEventPublisher events;

    public NotificationService(NotificationRepository notifications, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.events = events;
    }

    /** Joins the caller's transaction so a notification is never stored for a rolled-back change. */
    @Transactional
    public void notifyAll(UUID orgId, Collection<UUID> recipientIds, UUID incidentId, String type, String title,
                          String message) {
        List<UUID> distinct = recipientIds.stream().distinct().toList();
        distinct.forEach(r -> notifications.save(new Notification(orgId, r, incidentId, type, title, message)));
        if (!distinct.isEmpty()) {
            events.publishEvent(new NotificationsCreatedEvent(orgId, distinct, title, message));
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(AuthenticatedUser caller, boolean unreadOnly, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        List<Notification> found = unreadOnly
                ? notifications.findByRecipientIdAndOrganizationIdAndReadAtIsNullOrderByCreatedAtDesc(
                        caller.membershipId(), caller.organizationId(), page)
                : notifications.findByRecipientIdAndOrganizationIdOrderByCreatedAtDesc(
                        caller.membershipId(), caller.organizationId(), page);
        return found.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(AuthenticatedUser caller) {
        return notifications.countByRecipientIdAndOrganizationIdAndReadAtIsNull(
                caller.membershipId(), caller.organizationId());
    }

    @Transactional
    public NotificationView markRead(AuthenticatedUser caller, UUID id) {
        Notification n = notifications.findByIdAndRecipientIdAndOrganizationId(
                        id, caller.membershipId(), caller.organizationId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
        }
        return view(n);
    }

    @Transactional
    public int markAllRead(AuthenticatedUser caller) {
        return notifications.markAllRead(caller.membershipId(), caller.organizationId(), Instant.now());
    }

    private NotificationView view(Notification n) {
        return new NotificationView(n.getId(), n.getIncidentId(), n.getType(), n.getTitle(), n.getMessage(),
                n.getCreatedAt(), n.getReadAt() != null);
    }
}
