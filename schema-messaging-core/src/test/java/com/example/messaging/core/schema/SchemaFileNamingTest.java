package com.example.messaging.core.schema;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test: this fixture table must stay byte-identical to
 * {@code com.example.schemagen.SchemaFileNamingTest} in {@code schema-gen-tools}. Both modules are
 * dependency-free of each other, so the naming algorithm is duplicated rather than shared — this
 * test (and its twin) is what catches any drift between the two copies.
 */
class SchemaFileNamingTest {

    @ParameterizedTest
    @CsvSource({
            "OrderCreated,           order-created.schema.json",
            "OrderShipped,           order-shipped.schema.json",
            "OrderCancelled,         order-cancelled.schema.json",
            "CustomerRegistered,     customer-registered.schema.json",
            "CustomerAddressAdded,   customer-address-added.schema.json",
            "CustomerTierChanged,    customer-tier-changed.schema.json"
    })
    void toFileNameConvertsToKebabCase(String simpleClassName, String expectedFileName) {
        assertThat(SchemaFileNaming.toFileName(simpleClassName.trim())).isEqualTo(expectedFileName.trim());
    }
}
