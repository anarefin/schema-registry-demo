package com.example.messaging.core.mapping;

import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;

/**
 * Associates a Java type with its Apicurio schema coordinates, wire format, and AMQP routing key.
 * Each contracts module contributes one {@code TypeMapping} bean (spec §10.3/§10.4).
 *
 * <p>Example (from order-contracts configuration):
 * <pre>
 *   new TypeMapping(
 *       OrderCreated.class,
 *       new SchemaCoordinates("events.orders", "OrderCreated", null),
 *       SchemaType.JSON,
 *       "orders.created"
 *   )
 * </pre>
 */
public record TypeMapping(
        Class<?> javaType,
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        String routingKey
) {}
