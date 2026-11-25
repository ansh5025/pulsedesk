package com.pulsedesk.monitoring;

import java.util.UUID;

/** Published once when a DOWN service passes a health check again. */
public record ServiceRecoveredEvent(UUID organizationId, UUID serviceId, UUID configId) {
}
