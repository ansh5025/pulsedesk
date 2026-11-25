package com.pulsedesk.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends queued emails over SMTP. A failure propagates so the retry policy and dead-letter queue take over. */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessagingProperties props;

    public EmailConsumer(ObjectProvider<JavaMailSender> mailSender, MessagingProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE, containerFactory = "emailListenerFactory")
    public void handle(EmailMessage message) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            log.warn("spring.mail.host is not configured; dropping email '{}'", message.subject());
            return;
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(props.from());
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        sender.send(mail);
    }
}
