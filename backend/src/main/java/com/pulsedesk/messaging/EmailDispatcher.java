package com.pulsedesk.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Hands emails to RabbitMQ. A broker outage is logged and swallowed so it can never fail an API request. */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final MessagingProperties props;
    private final ObjectProvider<RabbitTemplate> rabbit;

    public EmailDispatcher(MessagingProperties props, ObjectProvider<RabbitTemplate> rabbit) {
        this.props = props;
        this.rabbit = rabbit;
    }

    public void dispatch(EmailMessage message) {
        if (!props.enabled() || message.to() == null || message.to().isBlank()) {
            return;
        }
        RabbitTemplate template = rabbit.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            template.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
        } catch (AmqpException | IllegalStateException e) {
            log.warn("Could not queue email '{}' (broker unavailable?): {}", message.subject(), e.getMessage());
        }
    }
}
