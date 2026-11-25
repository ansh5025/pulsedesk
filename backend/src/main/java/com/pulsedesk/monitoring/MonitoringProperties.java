package com.pulsedesk.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * allowPrivateTargets must stay false in production: it disables the SSRF protection that blocks
 * loopback, private and link-local health-check targets.
 */
@ConfigurationProperties(prefix = "app.monitoring")
public record MonitoringProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10000") long pollIntervalMs,
        @DefaultValue("false") boolean allowPrivateTargets) {
}
