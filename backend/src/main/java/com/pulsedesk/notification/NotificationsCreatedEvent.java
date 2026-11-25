package com.pulsedesk.notification;

import java.util.List;
import java.util.UUID;

/** Recipient ids are membership ids. */
public record NotificationsCreatedEvent(UUID organizationId, List<UUID> recipientIds, String title, String message) {
}
