package com.pulsedesk.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** analyticsTtlSeconds = 0 disables analytics caching. */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(@DefaultValue("60") long analyticsTtlSeconds) {
}
