package com.example.amqp.topology.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-classpath stand-in for the real {@code event-contract-kit} marker. Same FQCN so
 * {@link com.example.schemagen.GenerateSchemaScanner} can match by name without a compile
 * dependency on the kit.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface GenerateSchema {}
