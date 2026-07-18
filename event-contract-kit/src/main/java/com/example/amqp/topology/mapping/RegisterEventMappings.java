package com.example.amqp.topology.mapping;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Imports the shared {@link EventMappingRegistrar} for one event package. A domain's
 * {@code *-contracts} auto-configuration meta-annotates itself with this to register one named
 * {@link TypeMapping} bean per {@link EventMapping}-annotated record in {@link #value()} — replacing
 * the hand-written per-event {@code @Bean} methods. The registrar reads only the build-time index
 * (no classpath package scan) and skips any bean an application has already defined, so app
 * overrides win.
 *
 * <p>{@link #value()} is matched with <em>exact package equality</em> — a record in a sub-package is
 * not registered by its parent's import.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(EventMappingRegistrar.class)
public @interface RegisterEventMappings {

    /** The exact event package whose indexed records to register (no prefix matching). */
    String value();
}
