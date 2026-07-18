package com.example.amqp.topology.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-classpath stand-in for the real {@code event-contract-kit} annotation. Same FQCN and
 * attribute names so {@link com.example.schemagen.EventMappingValidator} can read it reflectively
 * without a compile dependency on the kit.
 *
 * <p>Deliberately omits the real annotation's {@code schemaType()} attribute: the build-time
 * validator never reads it, so a {@code SchemaType} stand-in would be dead weight.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventMapping {

    String groupId();

    String exchange();

    String routingKey();

    String artifactId() default "";
}
