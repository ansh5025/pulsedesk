package com.pulsedesk.monitoring;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TargetValidatorTest {

    private final TargetValidator strict = new TargetValidator(new MonitoringProperties(true, 10000, false));
    private final TargetValidator permissive = new TargetValidator(new MonitoringProperties(true, 10000, true));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/health",
            "http://localhost:8080/health",
            "http://10.0.0.5/health",
            "http://172.16.3.4/health",
            "http://192.168.1.10/health",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/health",
            "http://0.0.0.0/health",
            "http://[::1]/health",
            "http://[fd00::1]/health",
            "ftp://example.com/file",
            "file:///etc/passwd",
            "http://user:pass@8.8.8.8/health",
            "not a url"
    })
    void rejectsUnsafeTargets(String url) {
        assertThrows(IllegalArgumentException.class, () -> strict.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/health", "https://1.1.1.1:443/status"})
    void acceptsPublicTargets(String url) {
        assertDoesNotThrow(() -> strict.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:9000/health", "http://localhost/health"})
    void permissiveModeAllowsLocalTargets(String url) {
        assertDoesNotThrow(() -> permissive.validate(url));
    }
}
