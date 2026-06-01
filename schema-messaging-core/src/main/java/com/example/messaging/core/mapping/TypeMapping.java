package com.example.messaging.core.mapping;

import com.example.messaging.core.model.SchemaCoordinates;
import com.example.messaging.core.model.SchemaType;

/**
 * Associates a Java type with its Apicurio schema coordinates, wire format, and AMQP routing key.
 * Each contracts module contributes one {@code TypeMapping} bean (spec §10.3/§10.4).
 *
 * <p>Example (from customer-contracts auto-configuration):
 * <pre>
 *   new TypeMapping(
 *       CustomerRegistered.class,
 *       new SchemaCoordinates("events.customers", "CustomerRegistered", null),
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
