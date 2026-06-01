package com.example.consumer.config;

import com.example.consumer.amqp.DlxMessageRecoverer;
import com.example.consumer.amqp.DlxRoutingAdvice;
import com.example.messaging.core.consumer.EventConsumerSupport;
import com.example.messaging.core.converter.SchemaAwareMessageConverter;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Full DLX/retry/DLQ topology declared idempotently on consumer startup (spec §9 / T-5.1).
 *
 * <p>Topology:
 * <pre>
 *   events.exchange ──► customers.registered.queue ──► [listener]
 *                  └──► orders.created.queue        ──► [listener]
 *
 *   On transient failure (retry count &lt; 3):
 *     events.retry.exchange ──► *.retry.5s.queue  (TTL tier0, x-dlx → events.exchange)
 *                           └──► *.retry.30s.queue (TTL tier1, x-dlx → events.exchange)
 *                           └──► *.retry.5m.queue  (TTL tier2, x-dlx → events.exchange)
 *
 *   On permanent failure or retries exhausted:
 *     events.dlx ──► customers.registered.dlq
 *               └──► orders.created.dlq
 * </pre>
 *
 * <p>TTL ladder is configurable for tests (defaults: 5 s / 30 s / 5 m).
 */
@Configuration
public class AmqpConfiguration {

    // ---- Exchange names ---------------------------------------------------
    public static final String EVENTS_EXCHANGE       = "events.exchange";
    public static final String EVENTS_DLX            = "events.dlx";
    public static final String EVENTS_RETRY_EXCHANGE = "events.retry.exchange";

    // ---- Routing keys (also used as DLQ dead-letter-routing-key) ----------
    public static final String CUSTOMERS_ROUTING_KEY = "customers.registered";
    public static final String ORDERS_ROUTING_KEY    = "orders.created";

    // ---- Queue names ------------------------------------------------------
    public static final String CUSTOMERS_REGISTERED_QUEUE = "customers.registered.queue";
    public static final String ORDERS_CREATED_QUEUE       = "orders.created.queue";
    public static final String CUSTOMERS_DLQ              = "customers.registered.dlq";
    public static final String ORDERS_DLQ                 = "orders.created.dlq";

    // ---- TTL ladder (configurable; test overrides via application.yml) ----
    @Value("${events.retry.tier0.ms:5000}")   private long tier0Ms;
    @Value("${events.retry.tier1.ms:30000}")  private long tier1Ms;
    @Value("${events.retry.tier2.ms:300000}") private long tier2Ms;

    // ---- Exchanges ---------------------------------------------------------

    @Bean TopicExchange eventsExchange()      { return new TopicExchange(EVENTS_EXCHANGE, true, false); }
    @Bean TopicExchange eventsDlx()           { return new TopicExchange(EVENTS_DLX, true, false); }
    @Bean TopicExchange eventsRetryExchange() { return new TopicExchange(EVENTS_RETRY_EXCHANGE, true, false); }

    // ---- Main queues -------------------------------------------------------

    @Bean
    Queue customersRegisteredQueue() {
        return QueueBuilder.durable(CUSTOMERS_REGISTERED_QUEUE).build();
    }

    @Bean
    Binding customersRegisteredBinding() {
        return BindingBuilder.bind(customersRegisteredQueue()).to(eventsExchange()).with(CUSTOMERS_ROUTING_KEY);
    }

    @Bean
    Queue ordersCreatedQueue() {
        return QueueBuilder.durable(ORDERS_CREATED_QUEUE).build();
    }

    @Bean
    Binding ordersCreatedBinding() {
        return BindingBuilder.bind(ordersCreatedQueue()).to(eventsExchange()).with(ORDERS_ROUTING_KEY);
    }

    // ---- DLQ queues --------------------------------------------------------

    @Bean Queue customersDlq() { return QueueBuilder.durable(CUSTOMERS_DLQ).build(); }
    @Bean Queue ordersDlq()    { return QueueBuilder.durable(ORDERS_DLQ).build(); }

    @Bean
    Binding customersDlqBinding() {
        return BindingBuilder.bind(customersDlq()).to(eventsDlx()).with(CUSTOMERS_ROUTING_KEY);
    }

    @Bean
    Binding ordersDlqBinding() {
        return BindingBuilder.bind(ordersDlq()).to(eventsDlx()).with(ORDERS_ROUTING_KEY);
    }

    // ---- Retry queues: customers (3 tiers) ---------------------------------

    @Bean Queue customersRetry5s()  { return retryQueue(CUSTOMERS_ROUTING_KEY, 0, tier0Ms); }
    @Bean Queue customersRetry30s() { return retryQueue(CUSTOMERS_ROUTING_KEY, 1, tier1Ms); }
    @Bean Queue customersRetry5m()  { return retryQueue(CUSTOMERS_ROUTING_KEY, 2, tier2Ms); }

    @Bean Binding customersRetry5sBinding()  { return retryBinding(customersRetry5s(),  CUSTOMERS_ROUTING_KEY, 0); }
    @Bean Binding customersRetry30sBinding() { return retryBinding(customersRetry30s(), CUSTOMERS_ROUTING_KEY, 1); }
    @Bean Binding customersRetry5mBinding()  { return retryBinding(customersRetry5m(),  CUSTOMERS_ROUTING_KEY, 2); }

    // ---- Retry queues: orders (3 tiers) ------------------------------------

    @Bean Queue ordersRetry5s()  { return retryQueue(ORDERS_ROUTING_KEY, 0, tier0Ms); }
    @Bean Queue ordersRetry30s() { return retryQueue(ORDERS_ROUTING_KEY, 1, tier1Ms); }
    @Bean Queue ordersRetry5m()  { return retryQueue(ORDERS_ROUTING_KEY, 2, tier2Ms); }

    @Bean Binding ordersRetry5sBinding()  { return retryBinding(ordersRetry5s(),  ORDERS_ROUTING_KEY, 0); }
    @Bean Binding ordersRetry30sBinding() { return retryBinding(ordersRetry30s(), ORDERS_ROUTING_KEY, 1); }
    @Bean Binding ordersRetry5mBinding()  { return retryBinding(ordersRetry5m(),  ORDERS_ROUTING_KEY, 2); }

    // ---- Listener container factory ----------------------------------------

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SchemaAwareMessageConverter converter,
            EventConsumerSupport consumerSupport,
            RabbitTemplate rabbitTemplate) {
        long[] delays = {tier0Ms, tier1Ms, tier2Ms};
        DlxMessageRecoverer recoverer = new DlxMessageRecoverer(
                consumerSupport, rabbitTemplate, EVENTS_DLX, EVENTS_RETRY_EXCHANGE, delays);
        DlxRoutingAdvice advice = new DlxRoutingAdvice(recoverer);

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(advice);
        return factory;
    }

    // ---- helpers -----------------------------------------------------------

    private Queue retryQueue(String baseRoutingKey, int tier, long ttlMs) {
        return QueueBuilder.durable(baseRoutingKey + ".retry." + tierSuffix(tier))
                .ttl((int) ttlMs)
                .deadLetterExchange(EVENTS_EXCHANGE)
                .deadLetterRoutingKey(baseRoutingKey)
                .build();
    }

    private Binding retryBinding(Queue queue, String baseRoutingKey, int tier) {
        return BindingBuilder.bind(queue)
                .to(eventsRetryExchange())
                .with(baseRoutingKey + ".retry." + tierSuffix(tier));
    }

    static String tierSuffix(int tier) {
        return switch (tier) {
            case 0 -> "5s";
            case 1 -> "30s";
            case 2 -> "5m";
            default -> "t" + tier;
        };
    }
}
