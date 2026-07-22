package com.example.contracts.orders.topology;

import com.example.amqp.topology.mapping.Mappings;
import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.contracts.orders.OrderCancelled;
import com.example.contracts.orders.OrderCreated;
import com.example.contracts.orders.OrderFulfilled;
import com.example.contracts.orders.OrderShipped;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end registration for the orders domain: build-generated {@code @EventMapping} beans
 * become named {@link TypeMapping}s with prior coordinates/routing/bean names; app overrides win;
 * mapping registration never declares exchanges.
 */
class OrderTypeMappingRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GeneratedEventTypeMappings.class);

    @Test
    void registersFourNamedMappingsWithPriorCoordinatesAndRouting() {
        runner.run(context -> {
            assertThat(context.getBeanNamesForType(TypeMapping.class)).containsExactlyInAnyOrder(
                    "orderCancelledMapping",
                    "orderCreatedMapping",
                    "orderFulfilledMapping",
                    "orderShippedMapping");

            assertMapping(context.getBean("orderCreatedMapping", TypeMapping.class),
                    OrderCreated.class, "OrderCreated", OrderEventRouting.CREATED_ROUTING_KEY);
            assertMapping(context.getBean("orderShippedMapping", TypeMapping.class),
                    OrderShipped.class, "OrderShipped", OrderEventRouting.SHIPPED_ROUTING_KEY);
            assertMapping(context.getBean("orderCancelledMapping", TypeMapping.class),
                    OrderCancelled.class, "OrderCancelled", OrderEventRouting.CANCELLED_ROUTING_KEY);
            assertMapping(context.getBean("orderFulfilledMapping", TypeMapping.class),
                    OrderFulfilled.class, "OrderFulfilled", OrderEventRouting.FULFILLED_ROUTING_KEY);
        });
    }

    @Test
    void applicationBeanOverrideWinsWhileOthersStillRegister() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppOverride.class, GeneratedEventTypeMappings.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(TypeMapping.class)).containsExactlyInAnyOrder(
                            "orderCancelledMapping",
                            "orderCreatedMapping",
                            "orderFulfilledMapping",
                            "orderShippedMapping");
                    assertThat(context.getBean("orderCreatedMapping", TypeMapping.class).routingKey())
                            .isEqualTo("app.override");
                    assertThat(context.getBean("orderShippedMapping", TypeMapping.class).routingKey())
                            .isEqualTo(OrderEventRouting.SHIPPED_ROUTING_KEY);
                });
    }

    @Test
    void mappingAutoConfigurationDeclaresNoExchanges() {
        runner.run(context -> assertThat(context.getBeanNamesForType(TopicExchange.class)).isEmpty());
    }

    @Test
    void publisherTopologyStillDeclaresExchangesWhenImported() {
        new ApplicationContextRunner()
                .withUserConfiguration(GeneratedEventTypeMappings.class, OrdersPublisherTopology.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(TopicExchange.class))
                            .containsExactlyInAnyOrder(
                                    OrderEventRouting.BEAN_EXCHANGE,
                                    OrderEventRouting.BEAN_DLX,
                                    OrderEventRouting.BEAN_RETRY_EXCHANGE);
                    assertThat(context.getBean(OrderEventRouting.BEAN_EXCHANGE, TopicExchange.class)
                            .getName()).isEqualTo(OrderEventRouting.EXCHANGE);
                    assertThat(context.getBean(OrderEventRouting.BEAN_DLX, TopicExchange.class)
                            .getName()).isEqualTo(OrderEventRouting.DLX);
                    assertThat(context.getBean(OrderEventRouting.BEAN_RETRY_EXCHANGE, TopicExchange.class)
                            .getName()).isEqualTo(OrderEventRouting.RETRY_EXCHANGE);
                });
    }

    @Test
    void applicationExchangeOverrideWinsWhilePublisherTopologyProvidesTheOtherTwo() {
        new ApplicationContextRunner()
                .withUserConfiguration(AppExchangeOverride.class, OrdersPublisherTopology.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(TopicExchange.class))
                            .containsExactlyInAnyOrder(
                                    OrderEventRouting.BEAN_EXCHANGE,
                                    OrderEventRouting.BEAN_DLX,
                                    OrderEventRouting.BEAN_RETRY_EXCHANGE);
                    assertThat(context.getBean(OrderEventRouting.BEAN_EXCHANGE, TopicExchange.class)
                            .getName()).isEqualTo("app.override.exchange");
                });
    }

    private static void assertMapping(TypeMapping mapping, Class<?> javaType, String artifactId,
                                      String routingKey) {
        assertThat(mapping.javaType()).isEqualTo(javaType);
        assertThat(mapping.coordinates()).isEqualTo(new SchemaCoordinates("events.orders", artifactId));
        assertThat(mapping.schemaType()).isEqualTo(SchemaType.JSON);
        assertThat(mapping.routingKey()).isEqualTo(routingKey);
        assertThat(mapping.exchange()).isEqualTo(OrderEventRouting.EXCHANGE);
        assertThat(mapping.coordinates().artifactId()).isEqualTo(javaType.getSimpleName());
    }

    @Configuration(proxyBeanMethods = false)
    static class AppOverride {

        @Bean("orderCreatedMapping")
        TypeMapping orderCreatedMapping() {
            return Mappings.forDomain("events.orders", OrderEventRouting.EXCHANGE)
                    .json(OrderCreated.class, "app.override");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AppExchangeOverride {

        @Bean(OrderEventRouting.BEAN_EXCHANGE)
        TopicExchange ordersExchangeOverride() {
            return new TopicExchange("app.override.exchange");
        }
    }
}
