package com.example.messaging.core.consumer;

import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlxMessageRecovererTest {

    @Mock private EventConsumerSupport consumerSupport;
    @Mock private RabbitTemplate rabbitTemplate;

    @Test
    void recover_nullReceivedExchange_throwsIllegalState() {
        when(consumerSupport.classify(any())).thenReturn(RoutingDecision.DLQ_DIRECT);

        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("orders.created");
        // receivedExchange left null

        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000});

        assertThatThrownBy(() -> recoverer.recover(new Message(new byte[0], props), new RuntimeException("boom")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receivedExchange");
    }

    @Test
    void recover_permanent_sendsToDlxDerivedFromReceivedExchange() {
        when(consumerSupport.classify(any())).thenReturn(RoutingDecision.DLQ_DIRECT);

        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("events.orders.exchange");
        props.setReceivedRoutingKey("orders.created");

        Message message = new Message(new byte[0], props);
        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000});

        recoverer.recover(message, new RuntimeException("boom"));

        verify(consumerSupport).populateFailureHeaders(eq(message), any(), eq(RoutingDecision.DLQ_DIRECT));
        verify(rabbitTemplate).send(eq("events.orders.dlx"), eq("orders.created"), eq(message));
    }

    @Test
    void recover_transient_sendsToRetryExchange() {
        when(consumerSupport.classify(any())).thenReturn(RoutingDecision.RETRY);

        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("events.customers.exchange");
        props.setReceivedRoutingKey("customers.registered");
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, 0);

        Message message = new Message(new byte[0], props);
        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000});

        recoverer.recover(message, new RuntimeException("downstream"));

        verify(rabbitTemplate).send(
                eq("events.customers.retry.exchange"),
                eq("customers.registered.retry.5s"),
                eq(message));
    }
}
