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
                    assertThat(context.getBean("customerRegisteredMapping", TypeMapping.class).getRoutingKey())
                            .isEqualTo("app.override");
                    assertThat(context.getBean("customerAddressAddedMapping", TypeMapping.class).getRoutingKey())
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
                        GeneratedEventTypeMappings.class, CustomersPublisherTopology.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(TopicExchange.class))
                            .containsExactlyInAnyOrder(
                                    CustomerEventRouting.BEAN_EXCHANGE,
                                    CustomerEventRouting.BEAN_DLX,
                                    CustomerEventRouting.BEAN_RETRY_EXCHANGE);
                    assertThat(context.getBean(CustomerEventRouting.BEAN_EXCHANGE, TopicExchange.class)
                            .getName()).isEqualTo(CustomerEventRouting.EXCHANGE);
                    assertThat(context.getBean(CustomerEventRouting.BEAN_DLX, TopicExchange.class)
                            .getName()).isEqualTo(CustomerEventRouting.DLX);
                    assertThat(context.getBean(CustomerEventRouting.BEAN_RETRY_EXCHANGE, TopicExchange.class)
                            .getName()).isEqualTo(CustomerEventRouting.RETRY_EXCHANGE);
                });
    }

    @Test
    void applicationExchangeOverrideWinsWhilePublisherTopologyProvidesTheOtherTwo() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppExchangeOverride.class, CustomersPublisherTopology.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(TopicExchange.class))
                            .containsExactlyInAnyOrder(
                                    CustomerEventRouting.BEAN_EXCHANGE,
                                    CustomerEventRouting.BEAN_DLX,
                                    CustomerEventRouting.BEAN_RETRY_EXCHANGE);
                    assertThat(context.getBean(CustomerEventRouting.BEAN_EXCHANGE, TopicExchange.class)
                            .getName()).isEqualTo("app.override.exchange");
                });
    }

    private static void assertMapping(TypeMapping mapping, Class<?> javaType, String artifactId,
                                      String routingKey) {
        assertThat(mapping.getJavaType()).isEqualTo(javaType);
        assertThat(mapping.getCoordinates()).isEqualTo(new SchemaCoordinates("events.customers", artifactId));
        assertThat(mapping.getSchemaType()).isEqualTo(SchemaType.JSON);
        assertThat(mapping.getRoutingKey()).isEqualTo(routingKey);
        assertThat(mapping.getExchange()).isEqualTo(CustomerEventRouting.EXCHANGE);
        assertThat(mapping.getCoordinates().getArtifactId()).isEqualTo(javaType.getSimpleName());
    }

    @Configuration(proxyBeanMethods = false)
    static class AppOverride {

        @Bean("customerRegisteredMapping")
        TypeMapping customerRegisteredMapping() {
            return Mappings.forDomain("events.customers", CustomerEventRouting.EXCHANGE)
                    .json(CustomerRegistered.class, "app.override");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AppExchangeOverride {

        @Bean(CustomerEventRouting.BEAN_EXCHANGE)
        TopicExchange customersExchangeOverride() {
            return new TopicExchange("app.override.exchange");
        }
    }
}
