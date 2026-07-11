package com.example.amqp.topology.mapping;

/**
 * Associates a Java type with its schema coordinates, wire format, AMQP routing key, and
 * AMQP exchange. Each domain's {@code *-contracts} module contributes one {@code TypeMapping}
 * bean per event (spec §10.3/§10.4; exchange added by
 * {@code spec/simplified-publish-and-listen.md}).
 *
 * <p>Example:
 * <pre>
 *   new TypeMapping(
 *       CustomerRegistered.class,
 *       new SchemaCoordinates("events.customers", "CustomerRegistered"),
 *       SchemaType.JSON,
 *       "customers.registered",
 *       "events.customers.exchange"
 *   )
 * </pre>
 */
public record TypeMapping(
        Class<?> javaType,
        SchemaCoordinates coordinates,
        SchemaType schemaType,
        String routingKey,
        String exchange
) {}
