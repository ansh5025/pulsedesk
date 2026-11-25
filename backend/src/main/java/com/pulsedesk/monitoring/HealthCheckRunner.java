package com.pulsedesk.monitoring;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HealthCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckRunner.class);

    private final HealthCheckConfigRepository configs;
    private final HealthProbe probe;
    private final HealthCheckRecorder recorder;

    public HealthCheckRunner(HealthCheckConfigRepository configs, HealthProbe probe, HealthCheckRecorder recorder) {
        this.configs = configs;
        this.probe = probe;
        this.recorder = recorder;
    }

    /** Runs every due check concurrently on virtual threads and waits for all of them. */
    public void runDueChecks() {
        List<HealthCheckConfig> due = configs.findDue();
        if (due.isEmpty()) {
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (HealthCheckConfig config : due) {
                executor.submit(() -> check(config));
            }
        }
    }

    public void checkNow(UUID configId) {
        configs.findById(configId).ifPresent(this::check);
    }

    private void check(HealthCheckConfig config) {
        try {
            recorder.record(config.getId(),
                    probe.execute(config.getUrl(), config.getTimeoutMs(), config.getExpectedStatus()));
        } catch (RuntimeException e) {
            log.error("Health check {} failed unexpectedly", config.getId(), e);
        }
    }
}
