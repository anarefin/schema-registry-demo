package com.example.contracts.customers.topology;

import com.example.amqp.topology.mapping.Mappings;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.contracts.customers.CustomerAddressAdded;
import com.example.contracts.customers.CustomerRegistered;
import com.example.contracts.customers.CustomerTierChanged;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end registration for the customers domain: build-generated {@code @EventMapping} beans
 * become named {@link TypeMapping}s with prior coordinates/routing/bean names; app overrides win;
 * mapping registration never declares exchanges.
 */
class CustomerTypeMappingRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GeneratedEventTypeMappings.class);

    @Test
    void registersThreeNamedMappingsWithPriorCoordinatesAndRouting() {
        runner.run(context -> {
            assertThat(context.getBeanNamesForType(TypeMapping.class)).containsExactlyInAnyOrder(
                    "customerAddressAddedMapping",
                    "customerRegisteredMapping",
                    "customerTierChangedMapping");

            assertMapping(context.getBean("customerRegisteredMapping", TypeMapping.class),
                    CustomerRegistered.class, "CustomerRegistered",
                    CustomerEventRouting.REGISTERED_ROUTING_KEY);
            assertMapping(context.getBean("customerAddressAddedMapping", TypeMapping.class),
                    CustomerAddressAdded.class, "CustomerAddressAdded",
                    CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY);
            assertMapping(context.getBean("customerTierChangedMapping", TypeMapping.class),
                    CustomerTierChanged.class, "CustomerTierChanged",
                    CustomerEventRouting.TIER_CHANGED_ROUTING_KEY);
        });
    }

    @Test
    void applicationBeanOverrideWinsWhileOthersStillRegister() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppOverride.class, GeneratedEventTypeMappings.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(TypeMapping.class)).containsExactlyInAnyOrder(
                            "customerAddressAddedMapping",
                            "customerRegisteredMapping",
                            "customerTierChangedMapping");
                    assertThat(context.getBean("customerRegisteredMapping", TypeMapping.class).routingKey())
                            .isEqualTo("app.override");
                    assertThat(context.getBean("customerAddressAddedMapping", TypeMapping.class).routingKey())
                            .isEqualTo(CustomerEventRouting.ADDRESS_ADDED_ROUTING_KEY);
                });
    }

    @Test
    void mappingAutoConfigurationDeclaresNoExchanges() {
        runner.run(context -> assertThat(context.getBeanNamesForType(TopicExchange.class)).isEmpty());
    }

    @Test
    void publisherTopologyStillDeclaresExchangesWhenImported() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        GeneratedEventTypeMappings.class, CustomerPublisherTopology.class)
                .run(context -> assertThat(context.getBeanNamesForType(TopicExchange.class))
                        .containsExactlyInAnyOrder(
                                CustomerEventRouting.BEAN_EXCHANGE,
                                CustomerEventRouting.BEAN_DLX,
                                CustomerEventRouting.BEAN_RETRY_EXCHANGE));
    }

    private static void assertMapping(TypeMapping mapping, Class<?> javaType, String artifactId,
                                      String routingKey) {
        assertThat(mapping.javaType()).isEqualTo(javaType);
        assertThat(mapping.coordinates()).isEqualTo(new SchemaCoordinates("events.customers", artifactId));
        assertThat(mapping.schemaType()).isEqualTo(SchemaType.JSON);
        assertThat(mapping.routingKey()).isEqualTo(routingKey);
        assertThat(mapping.exchange()).isEqualTo(CustomerEventRouting.EXCHANGE);
        assertThat(mapping.coordinates().artifactId()).isEqualTo(javaType.getSimpleName());
    }

    @Configuration(proxyBeanMethods = false)
    static class AppOverride {

        @Bean("customerRegisteredMapping")
        TypeMapping customerRegisteredMapping() {
            return Mappings.forDomain("events.customers", CustomerEventRouting.EXCHANGE)
                    .json(CustomerRegistered.class, "app.override");
        }
    }
}
