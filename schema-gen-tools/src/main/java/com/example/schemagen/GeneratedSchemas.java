package com.example.schemagen;

import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerTierChanged;
import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderShipped;

import java.util.List;

/**
 * The single source of truth for every generated contract schema: which event type maps to which
 * committed artifact file. Both the build-time {@link SchemaGeneratorCli} and the
 * {@code SchemaDeterminismTest} iterate this list, so adding a new event is a one-line change here.
 *
 * <p>Output paths are relative to the {@code schema-gen-tools} module directory (the working
 * directory for both the {@code exec-maven-plugin} run and Surefire), writing into each owning
 * contract module's {@code src/main/resources/schemas/}.
 */
public final class GeneratedSchemas {

    private GeneratedSchemas() {}

    /** (event type → committed schema file). */
    public record Target(Class<?> eventType, String relativePath) {}

    public static final List<Target> ALL = List.of(
            new Target(OrderCreated.class,   "../order-contracts/src/main/resources/schemas/order-created.schema.json"),
            new Target(OrderShipped.class,   "../order-contracts/src/main/resources/schemas/order-shipped.schema.json"),
            new Target(OrderCancelled.class, "../order-contracts/src/main/resources/schemas/order-cancelled.schema.json"),
            new Target(CustomerRegistered.class,   "../customer-contracts/src/main/resources/schemas/customer-registered.schema.json"),
            new Target(CustomerAddressAdded.class, "../customer-contracts/src/main/resources/schemas/customer-address-added.schema.json"),
            new Target(CustomerTierChanged.class,  "../customer-contracts/src/main/resources/schemas/customer-tier-changed.schema.json"));
}
