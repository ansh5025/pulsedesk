package com.pulsedesk.incident;

import com.pulsedesk.monitoring.ServiceDownEvent;
import com.pulsedesk.monitoring.ServiceRecoveredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Reconciles incidents with health results once the health-check transaction has committed. */
@Component
public class HealthIncidentListener {

    private static final Logger log = LoggerFactory.getLogger(HealthIncidentListener.class);

    private final IncidentService incidents;

    public HealthIncidentListener(IncidentService incidents) {
        this.incidents = incidents;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDown(ServiceDownEvent event) {
        try {
            incidents.openForServiceDown(event.organizationId(), event.serviceId(), event.consecutiveFailures(),
                    event.lastError());
        } catch (RuntimeException e) {
            log.error("Failed to open incident for service {}", event.serviceId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecovered(ServiceRecoveredEvent event) {
        try {
            incidents.resolveForRecovery(event.organizationId(), event.serviceId());
        } catch (RuntimeException e) {
            log.error("Failed to resolve incidents for service {}", event.serviceId(), e);
        }
    }
}
