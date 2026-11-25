package com.pulsedesk.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled        publish emails through RabbitMQ and consume them (off by default)
 * @param from           sender address
 * @param inviteBaseUrl  base URL used to build the invitation link in the email
 * @param maxAttempts    delivery attempts before a message is dead-lettered
 * @param retryBackoffMs initial backoff between attempts (doubles each time)
 */
@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(boolean enabled, String from, String inviteBaseUrl, Integer maxAttempts,
                                  Long retryBackoffMs) {

    public MessagingProperties {
        from = from == null || from.isBlank() ? "no-reply@pulsedesk.local" : from;
        inviteBaseUrl = inviteBaseUrl == null || inviteBaseUrl.isBlank()
                ? "http://localhost:3000/accept-invite" : inviteBaseUrl;
        maxAttempts = maxAttempts == null || maxAttempts < 1 ? 3 : maxAttempts;
        retryBackoffMs = retryBackoffMs == null || retryBackoffMs < 0 ? 1000L : retryBackoffMs;
    }
}
