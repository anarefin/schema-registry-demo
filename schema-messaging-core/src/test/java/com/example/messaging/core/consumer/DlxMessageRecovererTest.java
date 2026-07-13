package com.example.messaging.core.consumer;

import com.example.messaging.core.converter.SchemaMessageHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlxMessageRecovererTest {

    private static final String SERVICE_NAME = "consumer-service";

    @Mock private EventConsumerSupport consumerSupport;
    @Mock private RabbitTemplate rabbitTemplate;

    @Test
    void retryDelaysMs_returnsDefensiveCopy_mutatingReturnedArrayDoesNotAffectRecoverer() {
        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000}, SERVICE_NAME);

        long[] exposed = recoverer.retryDelaysMs();
        exposed[0] = 999_999;

        assertThat(recoverer.retryDelaysMs()[0]).isEqualTo(5_000);
    }

    @Test
    void recover_nullReceivedExchange_throwsIllegalState() {
        when(consumerSupport.classify(any())).thenReturn(RoutingDecision.DLQ_DIRECT);

        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("orders.created");
        // receivedExchange left null

        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000}, SERVICE_NAME);

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
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000}, SERVICE_NAME);

        recoverer.recover(message, new RuntimeException("boom"));

        verify(consumerSupport).populateFailureHeaders(eq(message), any(), eq(RoutingDecision.DLQ_DIRECT));
        verify(rabbitTemplate).send(eq("events.orders.dlx"), eq("orders.created.consumer-service"), eq(message));
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
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000}, SERVICE_NAME);

        recoverer.recover(message, new RuntimeException("downstream"));

        verify(rabbitTemplate).send(
                eq("events.customers.retry.exchange"),
                eq("customers.registered.consumer-service.retry.5s"),
                eq(message));
    }

    @Test
    void recover_transient_secondFailureDoesNotDoubleAppendServiceNameToRoutingKey() {
        // A message that already survived one retry-tier TTL expiry is redelivered to the main
        // queue via its private per-service binding, so receivedRoutingKey already carries the
        // ".consumer-service" suffix (see EventTopologyFactory.declarablesForEvent /
        // TopologyNaming.serviceRoutingKey). The recoverer must strip that suffix back to the
        // plain routing key before composing the next tier's key, not append it a second time.
        when(consumerSupport.classify(any())).thenReturn(RoutingDecision.RETRY);

        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("events.customers.exchange");
        props.setReceivedRoutingKey("customers.registered.consumer-service");
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, 1);

        Message message = new Message(new byte[0], props);
        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000}, SERVICE_NAME);

        recoverer.recover(message, new RuntimeException("downstream"));

        verify(rabbitTemplate).send(
                eq("events.customers.retry.exchange"),
                eq("customers.registered.consumer-service.retry.30s"),
                eq(message));
    }

    @Test
    void recover_permanentAfterRetries_dlqRoutingKeyHasServiceNameOnlyOnce() {
        when(consumerSupport.classify(any())).thenReturn(RoutingDecision.DLQ_DIRECT);

        MessageProperties props = new MessageProperties();
        props.setReceivedExchange("events.customers.exchange");
        props.setReceivedRoutingKey("customers.registered.consumer-service");
        props.setHeader(SchemaMessageHeaders.RETRY_COUNT, 3);

        Message message = new Message(new byte[0], props);
        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, new long[]{5_000, 30_000, 300_000}, SERVICE_NAME);

        recoverer.recover(message, new RuntimeException("downstream"));

        verify(rabbitTemplate).send(
                eq("events.customers.dlx"),
                eq("customers.registered.consumer-service"),
                eq(message));
    }
}
