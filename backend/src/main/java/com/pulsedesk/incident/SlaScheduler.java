package com.pulsedesk.incident;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.sla", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlaScheduler.class);

    private final SlaEscalationService escalation;

    public SlaScheduler(SlaEscalationService escalation) {
        this.escalation = escalation;
    }

    @Scheduled(fixedDelayString = "${app.sla.scan-interval-ms:30000}")
    void scan() {
        int escalated = escalation.scan(Instant.now());
        if (escalated > 0) {
            log.info("Escalated {} SLA breaches", escalated);
        }
    }
}
