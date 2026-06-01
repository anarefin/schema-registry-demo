package com.example.consumer.amqp;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

/**
 * AOP advice applied to the listener container's message-listener chain.
 * Intercepts any exception thrown during listener invocation (including converter failures),
 * delegates to {@link DlxMessageRecoverer} for DLQ/retry routing, then suppresses the
 * exception so the container ACKs the original message (spec §9 / T-5.2).
 *
 * <p>The first argument to the intercepted method is always the raw AMQP {@link Message}.
 * If no {@link Message} is found (unexpected invocation context), the exception re-throws.
 */
public class DlxRoutingAdvice implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DlxRoutingAdvice.class);

    private final DlxMessageRecoverer recoverer;

    public DlxRoutingAdvice(DlxMessageRecoverer recoverer) {
        this.recoverer = recoverer;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // Spring AMQP intercepts invokeListener(Channel, Message) — scan all args for Message
        Message message = null;
        for (Object arg : invocation.getArguments()) {
            if (arg instanceof Message m) {
                message = m;
                break;
            }
        }
        try {
            return invocation.proceed();
        } catch (Throwable t) {
            if (message == null) {
                throw t; // can't route without a message
            }
            Exception ex = t instanceof Exception e ? e : new RuntimeException(t);
            try {
                recoverer.recover(message, ex);
            } catch (Exception routingEx) {
                // routing itself failed (e.g. broker down); log and re-throw to trigger nack
                log.error("DLX routing failed for message; original cause follows", routingEx);
                throw t;
            }
            return null; // suppress original exception → container ACKs the message
        }
    }
}
