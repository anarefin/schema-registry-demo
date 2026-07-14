package com.example.amqp.topology.mapping;

/**
 * Domain-scoped builder for {@link TypeMapping} beans. Binds group, exchange, and
 * {@link SchemaType#JSON} once; {@link #json(Class, String)} defaults the artifact id to the
 * Java type's simple name.
 */
public final class Mappings {

    private final String group;
    private final String exchange;

    private Mappings(String group, String exchange) {
        this.group = group;
        this.exchange = exchange;
    }

    public static Mappings forDomain(String group, String exchange) {
        return new Mappings(group, exchange);
    }

    public TypeMapping json(Class<?> javaType, String routingKey) {
        return json(javaType, javaType.getSimpleName(), routingKey);
    }

    public TypeMapping json(Class<?> javaType, String artifactId, String routingKey) {
        return new TypeMapping(
                javaType,
                new SchemaCoordinates(group, artifactId),
                SchemaType.JSON,
                routingKey,
                exchange);
    }
}
