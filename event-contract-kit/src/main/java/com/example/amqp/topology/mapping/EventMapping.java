package com.example.amqp.topology.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative schema identity and AMQP route for a code-first event class (spec §13). Sits
 * alongside {@link GenerateSchema}: the marker says "generate my JSON Schema", this annotation says
 * "here is my registry group/artifact and my publish exchange/routing key". {@code schema-gen-tools}
 * validates the pair at {@code process-classes} and writes the build-time event index; contracts
 * auto-configuration reads that index and registers one named {@link TypeMapping} bean per event.
 *
 * <p>Domain-agnostic and deliberately framework-free: it carries no Spring annotations and no
 * queue/DLQ/retry/TTL/consumer/service/durability/schema-version fields (those are core and
 * publisher-topology concerns, not contract metadata). Reuse each domain's {@code *EventRouting}
 * constants for {@link #exchange()} and {@link #routingKey()} rather than deriving them from class
 * names. Annotation metadata never declares an exchange.
 *
 * <p>Retention is {@link RetentionPolicy#RUNTIME} so both the build-time scanner (after
 * {@code Class.forName}) and the runtime registrar can read it reflectively. One mapping per Java
 * type — the annotation is intentionally not repeatable.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventMapping {

    /** Apicurio group id, e.g. {@code events.orders}. Required and must be non-blank. */
    String groupId();

    /** Publisher-owned exchange the event routes through. Required and must be non-blank. */
    String exchange();

    /** Routing key used when publishing the event. Required and must be non-blank. */
    String routingKey();

    /** Apicurio artifact id. Empty means {@code javaType.getSimpleName()}. */
    String artifactId() default "";

    /**
     * Wire format / schema technology. Defaults to {@link SchemaType#JSON}.
     * Only {@link SchemaType#JSON} is supported by the runtime registrar today; any other value
     * fails fast at index load.
     */
    SchemaType schemaType() default SchemaType.JSON;
}
