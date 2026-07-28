package com.example.amqp.topology.mapping;

import java.util.Objects;

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
public final class TypeMapping {

    private final Class<?> javaType;
    private final SchemaCoordinates coordinates;
    private final SchemaType schemaType;
    private final String routingKey;
    private final String exchange;

    public TypeMapping(
            Class<?> javaType,
            SchemaCoordinates coordinates,
            SchemaType schemaType,
            String routingKey,
            String exchange) {
        this.javaType = javaType;
        this.coordinates = coordinates;
        this.schemaType = schemaType;
        this.routingKey = routingKey;
        this.exchange = exchange;
    }

    public Class<?> getJavaType() {
        return javaType;
    }

    public SchemaCoordinates getCoordinates() {
        return coordinates;
    }

    public SchemaType getSchemaType() {
        return schemaType;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getExchange() {
        return exchange;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypeMapping that)) {
            return false;
        }
        return Objects.equals(javaType, that.javaType)
                && Objects.equals(coordinates, that.coordinates)
                && schemaType == that.schemaType
                && Objects.equals(routingKey, that.routingKey)
                && Objects.equals(exchange, that.exchange);
    }

    @Override
    public int hashCode() {
        return Objects.hash(javaType, coordinates, schemaType, routingKey, exchange);
    }

    @Override
    public String toString() {
        return "TypeMapping[javaType=" + javaType
                + ", coordinates=" + coordinates
                + ", schemaType=" + schemaType
                + ", routingKey=" + routingKey
                + ", exchange=" + exchange + "]";
    }
}
