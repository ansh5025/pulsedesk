package com.pulsedesk.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;

class EmailDispatcherTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RabbitTemplate> provider(RabbitTemplate t) {
        ObjectProvider<RabbitTemplate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(t);
        return p;
    }

    private static MessagingProperties props(boolean enabled) {
        return new MessagingProperties(enabled, null, null, null, null);
    }

    @Test
    void brokerOutageNeverPropagates() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new RuntimeException("down"))).when(template)
                .convertAndSend(anyString(), anyString(), any(Object.class));
        EmailDispatcher d = new EmailDispatcher(props(true), provider(template));
        assertDoesNotThrow(() -> d.dispatch(new EmailMessage("a@b.test", "s", "b")));
    }

    @Test
    void disabledOrAddresslessMessagesAreNotQueued() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        new EmailDispatcher(props(false), provider(template)).dispatch(new EmailMessage("a@b.test", "s", "b"));
        new EmailDispatcher(props(true), provider(template)).dispatch(new EmailMessage(" ", "s", "b"));
        verify(template, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }
}
