package com.example.messaging.core.amqp;

import com.example.messaging.core.config.SchemaMessagingAutoConfiguration;
import com.example.messaging.core.mapping.TypeMapping;
import com.example.messaging.core.mapping.TypeMappingRegistry;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * Single, data-driven AMQP topology for every event (spec §9, code-first D3). Replaces the
 * per-contracts {@code *EventTopologyAutoConfiguration} classes: the 3 shared exchanges are
 * declared exactly once, and the full per-event topology is derived by iterating the
 * {@link TypeMappingRegistry} — so adding a seventh event needs no edit here.
 *
 * <p>Every name is derived from {@link TypeMapping#routingKey()} ({@code rk}): main queue
 * {@code rk.queue} bound to {@code events.exchange}, DLQ {@code rk.dlq} bound to {@code events.dlx},
 * and the 3-tier TTL retry queues bound to {@code events.retry.exchange}. Contracts carry no
 * AMQP/Spring code.
 */
@AutoConfiguration(after = SchemaMessagingAutoConfiguration.class)
@ConditionalOnBean(TypeMappingRegistry.class)
public class EventTopologyAutoConfiguration {

    /** Suffix conventions shared with each contracts module's {@code *EventRouting} constants. */
    public static final String QUEUE_SUFFIX = ".queue";
    public static final String DLQ_SUFFIX   = ".dlq";

    @Value("${events.retry.tier0.ms:5000}")   private long tier0Ms;
    @Value("${events.retry.tier1.ms:30000}")  private long tier1Ms;
    @Value("${events.retry.tier2.ms:300000}") private long tier2Ms;

    // ---- Shared exchanges (declared once) ----

    @Bean(EventExchanges.BEAN_EVENTS_EXCHANGE)
    @ConditionalOnMissingBean(name = EventExchanges.BEAN_EVENTS_EXCHANGE)
    public TopicExchange eventsExchange() {
        return new TopicExchange(EventExchanges.EVENTS_EXCHANGE, true, false);
    }

    @Bean(EventExchanges.BEAN_EVENTS_DLX)
    @ConditionalOnMissingBean(name = EventExchanges.BEAN_EVENTS_DLX)
    public TopicExchange eventsDlx() {
        return new TopicExchange(EventExchanges.EVENTS_DLX, true, false);
    }

    @Bean(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE)
    @ConditionalOnMissingBean(name = EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE)
    public TopicExchange eventsRetryExchange() {
        return new TopicExchange(EventExchanges.EVENTS_RETRY_EXCHANGE, true, false);
    }

    // ---- Per-event topology, derived from the registry ----

    /**
     * One aggregated {@link Declarables} carrying the main queue + binding, DLQ + binding, and
     * 3-tier retry queues + bindings for every registered {@link TypeMapping}. {@code RabbitAdmin}
     * declares them all idempotently on startup.
     */
    @Bean
    @ConditionalOnMissingBean(name = "eventTopologyDeclarables")
    public Declarables eventTopologyDeclarables(
            TypeMappingRegistry registry,
            @Qualifier(EventExchanges.BEAN_EVENTS_EXCHANGE) TopicExchange eventsExchange,
            @Qualifier(EventExchanges.BEAN_EVENTS_DLX) TopicExchange eventsDlx,
            @Qualifier(EventExchanges.BEAN_EVENTS_RETRY_EXCHANGE) TopicExchange eventsRetryExchange) {

        long[] tierTtls = {tier0Ms, tier1Ms, tier2Ms};
        List<Declarable> declarables = new ArrayList<>();

        for (TypeMapping mapping : registry.all()) {
            String rk = mapping.routingKey();

            // Main queue + binding to events.exchange.
            Queue mainQueue = QueueBuilder.durable(rk + QUEUE_SUFFIX).build();
            declarables.add(mainQueue);
            declarables.add(BindingBuilder.bind(mainQueue).to(eventsExchange).with(rk));

            // Dead-letter queue + binding to events.dlx.
            Queue dlq = QueueBuilder.durable(rk + DLQ_SUFFIX).build();
            declarables.add(dlq);
            declarables.add(BindingBuilder.bind(dlq).to(eventsDlx).with(rk));

            // 3-tier TTL retry queues + bindings to events.retry.exchange.
            for (int tier = 0; tier < tierTtls.length; tier++) {
                Queue retryQueue = RetryTopologyFactory.retryQueue(rk, tier, tierTtls[tier]);
                declarables.add(retryQueue);
                declarables.add(RetryTopologyFactory.retryBinding(retryQueue, eventsRetryExchange, rk, tier));
            }
        }
        return new Declarables(declarables);
    }
}
