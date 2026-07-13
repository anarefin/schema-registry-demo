package com.example.messaging.core.consumer;

import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlxRoutingAdviceTest {

    @Mock private DlxMessageRecoverer recoverer;
    @Mock private MethodInvocation invocation;

    @Test
    void recoverFailureRethrowsOriginalExceptionSoMessageDeadLetters() throws Throwable {
        Message message = new Message(new byte[0], new MessageProperties());
        RuntimeException listenerFailure = new RuntimeException("handler blew up");
        IllegalStateException routingFailure = new IllegalStateException("broker down during DLQ publish");

        when(invocation.getArguments()).thenReturn(new Object[] {message});
        when(invocation.proceed()).thenThrow(listenerFailure);
        doThrow(routingFailure).when(recoverer).recover(message, listenerFailure);

        DlxRoutingAdvice advice = new DlxRoutingAdvice(recoverer);

        // Must rethrow the original listener failure — not swallow it — so the container nacks
        // with requeue=false and RabbitMQ dead-letters via the main queue's x-dead-letter-exchange.
        assertThatThrownBy(() -> advice.invoke(invocation))
                .isSameAs(listenerFailure);

        verify(recoverer).recover(message, listenerFailure);
    }
}
