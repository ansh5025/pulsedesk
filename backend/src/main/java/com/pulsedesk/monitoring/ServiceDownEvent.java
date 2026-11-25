package com.pulsedesk.monitoring;

import java.util.UUID;

/** Published once when a service crosses its failure threshold. Phase 4 turns this into an incident. */
public record ServiceDownEvent(UUID organizationId, UUID serviceId, UUID configId, int consecutiveFailures,
                               String lastError) {
}
