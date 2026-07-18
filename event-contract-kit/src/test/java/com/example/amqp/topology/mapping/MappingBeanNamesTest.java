package com.example.amqp.topology.mapping;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MappingBeanNamesTest {

    static class OrderCreated {}

    static class CustomerAddressAdded {}

    static class URLEvent {}

    @Test
    void decapitalizesSimpleNameAndAppendsMapping() {
        assertThat(MappingBeanNames.forSimpleName("OrderCreated")).isEqualTo("orderCreatedMapping");
        assertThat(MappingBeanNames.forSimpleName("CustomerAddressAdded"))
                .isEqualTo("customerAddressAddedMapping");
    }

    @Test
    void preservesLeadingAcronymRunPerIntrospectorRules() {
        // Introspector.decapitalize leaves a leading run of capitals untouched.
        assertThat(MappingBeanNames.forSimpleName("URLEvent")).isEqualTo("URLEventMapping");
    }

    @Test
    void forTypeUsesTheJavaSimpleName() {
        assertThat(MappingBeanNames.forType(OrderCreated.class)).isEqualTo("orderCreatedMapping");
        assertThat(MappingBeanNames.forType(CustomerAddressAdded.class))
                .isEqualTo("customerAddressAddedMapping");
        assertThat(MappingBeanNames.forType(URLEvent.class)).isEqualTo("URLEventMapping");
    }
}
