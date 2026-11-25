package com.pulsedesk.monitoring;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.monitoring", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckScheduler.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final HealthCheckRunner runner;
    private final HealthCheckResultRepository results;

    public HealthCheckScheduler(HealthCheckRunner runner, HealthCheckResultRepository results) {
        this.runner = runner;
        this.results = results;
    }

    @Scheduled(fixedDelayString = "${app.monitoring.poll-interval-ms:10000}")
    void poll() {
        runner.runDueChecks();
    }

    @Scheduled(cron = "0 15 * * * *")
    void purgeOldResults() {
        int deleted = results.deleteOlderThan(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("Purged {} health check results older than {}", deleted, RETENTION);
        }
    }
}
