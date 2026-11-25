package com.pulsedesk.monitoring;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthCheckRecorder {

    private final HealthCheckConfigRepository configs;
    private final HealthCheckResultRepository results;
    private final ApplicationEventPublisher events;

    public HealthCheckRecorder(HealthCheckConfigRepository configs, HealthCheckResultRepository results,
                               ApplicationEventPublisher events) {
        this.configs = configs;
        this.results = results;
        this.events = events;
    }

    /** Reasserts DOWN after each threshold failure so a manually resolved incident can be replaced. */
    @Transactional
    public void record(UUID configId, ProbeOutcome outcome) {
        HealthCheckConfig config = configs.findById(configId).orElse(null);
        if (config == null) {
            return;
        }
        Instant now = Instant.now();
        results.save(new HealthCheckResult(config.getOrganizationId(), config.getId(), now, outcome));
        config.setLastCheckedAt(now);

        if (outcome.success()) {
            config.setConsecutiveFailures(0);
            if (config.getStatus() != HealthStatus.UP) {
                boolean recovered = config.getStatus() == HealthStatus.DOWN;
                config.setStatus(HealthStatus.UP);
                config.setLastStatusChangeAt(now);
                if (recovered) {
                    events.publishEvent(new ServiceRecoveredEvent(
                            config.getOrganizationId(), config.getServiceId(), config.getId()));
                }
            }
        } else {
            config.setConsecutiveFailures(config.getConsecutiveFailures() + 1);
            if (config.getConsecutiveFailures() >= config.getFailureThreshold()) {
                if (config.getStatus() != HealthStatus.DOWN) {
                    config.setStatus(HealthStatus.DOWN);
                    config.setLastStatusChangeAt(now);
                }
                events.publishEvent(new ServiceDownEvent(config.getOrganizationId(), config.getServiceId(),
                        config.getId(), config.getConsecutiveFailures(), outcome.error()));
            }
        }
        configs.save(config);
    }
}
