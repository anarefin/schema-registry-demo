package com.example.messaging.core.config;

import com.example.amqp.topology.mapping.SchemaCoordinates;
import com.example.amqp.topology.mapping.SchemaType;
import com.example.amqp.topology.mapping.TypeMapping;
import com.example.messaging.core.consumer.HandledEventTypesCache;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression for the publisher-owned-topology flip (spec ticket 02): a contracts jar on the
 * classpath no longer forces a service to declare broker topology. A context that carries a
 * contracts jar's contribution — {@code TypeMapping} data beans, but <em>no</em>
 * {@code @Import(*PublisherTopology)} and <em>no</em> {@code @BitsEventHandler} — must declare
 * <strong>zero</strong> {@link Declarable} topology beans and invoke {@code declareExchange}
 * <strong>zero</strong> times.
 *
 * <p>Core cannot depend on the {@code *-contracts} modules (machine-enforced ban), so the contracts
 * jar's contribution is reproduced with raw {@link TypeMapping} beans. Exchange {@code @Bean}s now
 * live only in the opt-in {@code *PublisherTopology} classes, which are absent here; core declares
 * queues/bindings via {@code rabbitAdmin} (never as {@link Declarable} beans) and only when a
 * handler is present — so with no handler the {@link RabbitAdmin} is never touched.
 */
class ContractsJarDeclaresNoTopologyWithoutHandlersTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RetryTierPropertiesAutoConfiguration.class,
                    ServiceQueueTopologyAutoConfiguration.class))
            .withUserConfiguration(ContractsJarSimulation.class)
            .withPropertyValues("spring.application.name=some-service");

    @Test
    void contractsJarWithoutImportOrHandlerDeclaresNoTopology() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // No exchange/queue/binding beans: the only source of exchange @Beans is the opt-in
            // *PublisherTopology, which is not imported here, and core declares nothing as beans.
            assertThat(context.getBeansOfType(Declarable.class)).isEmpty();

            // No @BitsEventHandler ⇒ ServiceQueueTopologyConfigurer is a no-op ⇒ no broker calls,
            // and crucially core never declares an exchange under any path.
            RabbitAdmin rabbitAdmin = context.getBean(RabbitAdmin.class);
            verify(rabbitAdmin, never()).declareExchange(any());
        });
    }

    /** Reproduces what a contracts jar contributes post-flip: plain {@code TypeMapping} data beans. */
    record ProbeA(String id) {}

    record ProbeB(String id) {}

    @Configuration(proxyBeanMethods = false)
    static class ContractsJarSimulation {

        @Bean
        TypeMapping probeAMapping() {
            return new TypeMapping(
                    ProbeA.class,
                    new SchemaCoordinates("events.orders", "ProbeA"),
                    SchemaType.JSON,
                    "orders.probe-a",
                    "events.orders.exchange");
        }

        @Bean
        TypeMapping probeBMapping() {
            return new TypeMapping(
                    ProbeB.class,
                    new SchemaCoordinates("events.customers", "ProbeB"),
                    SchemaType.JSON,
                    "customers.probe-b",
                    "events.customers.exchange");
        }

        @Bean
        TypeMappingRegistry typeMappingRegistry(List<TypeMapping> mappings) {
            return new TypeMappingRegistry(mappings);
        }

        @Bean
        HandledEventTypesCache handledEventTypesCache(
                ApplicationContext applicationContext, TypeMappingRegistry typeMappingRegistry) {
            return new HandledEventTypesCache(applicationContext, typeMappingRegistry);
        }

        @Bean
        RabbitAdmin rabbitAdmin() {
            return mock(RabbitAdmin.class);
        }
    }
}
