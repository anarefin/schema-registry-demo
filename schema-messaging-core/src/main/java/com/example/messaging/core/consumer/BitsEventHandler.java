package com.example.messaging.core.consumer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an event listener whose queue is resolved internally from the registered
 * {@link com.example.amqp.topology.mapping.TypeMapping} for the method's single parameter type —
 * no queue name or container factory needed on the annotation itself (spec
 * {@code simplified-publish-and-listen.md} D3). Registration is handled by
 * {@link BitsEventHandlerRegistrar}.
 *
 * <pre>
 *   {@code @BitsEventHandler}
 *   public void onCustomerRegistered(CustomerRegistered event) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BitsEventHandler {
}
