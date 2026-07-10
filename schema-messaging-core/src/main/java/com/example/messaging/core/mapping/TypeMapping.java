package com.example.messaging.core.mapping;

import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;

/**
 * Associates a Java type with its schema coordinates, wire format, and AMQP routing key.
 * Each service contributes one {@code TypeMapping} bean per event (spec §10.3/§10.4).
 *
 * <p>Example:
 * <pre>
 *   new TypeMapping(
 *       CustomerRegistered.class,
 *       new SchemaCoordinates("events.customers", "CustomerRegistered"),
 *       SchemaType.JSON,
 *       "customers.registered"
 *   )
 * </pre>
 */
public record TypeMapping(
        Class<?> javaType,
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        String routingKey
) {}
