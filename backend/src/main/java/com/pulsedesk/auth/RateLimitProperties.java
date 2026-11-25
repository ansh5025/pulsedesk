package com.pulsedesk.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        @DefaultValue("5") int loginMaxAttempts,
        @DefaultValue("20") int loginMaxAttemptsPerIp,
        @DefaultValue("900") long loginWindowSeconds) {
}
