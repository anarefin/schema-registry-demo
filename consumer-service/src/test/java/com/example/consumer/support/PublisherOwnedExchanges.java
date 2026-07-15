package com.example.consumer.support;

import com.example.contracts.customers.topology.CustomerPublisherTopology;
import com.example.contracts.orders.topology.OrderPublisherTopology;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Test-only stand-in for the publisher. In production the sole publisher (producer-service)
 * declares the orders/customers exchanges; the consumer only binds its private queues to them.
 * Consumer ITs run without a producer, so those exchanges would not otherwise exist — this config
 * imports both {@code *PublisherTopology} classes so the exchanges the consumer's bindings target
 * are present on the broker.
 *
 * <p>{@code @TestConfiguration} (not plain {@code @Configuration}) so it is excluded from
 * {@code ConsumerApplication}'s component scan — it lives under the scanned base package
 * {@code com.example.consumer} but must apply <em>only</em> where a test explicitly
 * {@code @Import}s it, mirroring the production opt-in model rather than leaking the exchanges into
 * every consumer context.
 */
@TestConfiguration
@Import({OrderPublisherTopology.class, CustomerPublisherTopology.class})
public class PublisherOwnedExchanges {
}
