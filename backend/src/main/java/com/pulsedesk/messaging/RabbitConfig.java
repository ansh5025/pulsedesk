package com.pulsedesk.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.messaging", name = "enabled", havingValue = "true")
public class RabbitConfig {

    public static final String EXCHANGE = "pulsedesk.email";
    public static final String QUEUE = "pulsedesk.email.send";
    public static final String ROUTING_KEY = "email.send";
    public static final String DEAD_EXCHANGE = "pulsedesk.email.dlx";
    public static final String DEAD_QUEUE = "pulsedesk.email.dead";

    @Bean
    DirectExchange emailExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    DirectExchange emailDeadExchange() {
        return new DirectExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    Queue emailQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_QUEUE)
                .build();
    }

    @Bean
    Queue emailDeadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    Binding emailBinding() {
        return BindingBuilder.bind(emailQueue()).to(emailExchange()).with(ROUTING_KEY);
    }

    @Bean
    Binding emailDeadBinding() {
        return BindingBuilder.bind(emailDeadQueue()).to(emailDeadExchange()).with(DEAD_QUEUE);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** Retries in-process with exponential backoff, then rejects without requeue so the broker dead-letters it. */
    @Bean
    SimpleRabbitListenerContainerFactory emailListenerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
                                                              ConnectionFactory connectionFactory,
                                                              MessagingProperties props) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(props.maxAttempts())
                .backOffOptions(props.retryBackoffMs(), 2.0, 30_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
